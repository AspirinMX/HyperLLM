package app.pagefault.hyperllm.gateway.proxy;

import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.translate.ProtocolTranslator;
import org.springframework.core.io.buffer.DataBuffer;

import java.io.InputStream;

/**
 * Inbound request byte storage that prevents G1GC Humongous allocations on large payloads.
 *
 * <p>Payloads at or below {@link #DEFAULT_CONTIGUOUS_THRESHOLD_BYTES} (512 KB) are stored in a
 * single contiguous {@code byte[]} so {@code JsonUtf8Text} and {@code ValidatedJsonObject} can
 * slice directly into it with zero string copying. Payloads exceeding 512 KB (such as multi-MB
 * agent contexts or base64 image batches) are segmented into fixed 256 KB pages and streamed to
 * Jackson via an {@link InputStream}, avoiding G1 Humongous Old-Gen allocations on any heap size.
 */
public final class RequestByteArena {

    public static final int DEFAULT_CONTIGUOUS_THRESHOLD_BYTES = 512 * 1024;
    public static final int DEFAULT_PAGE_SIZE_BYTES = 256 * 1024;

    private static final byte[] EMPTY_BYTES = new byte[0];
    public static final RequestByteArena EMPTY = new RequestByteArena(EMPTY_BYTES, null, 0);

    private byte[] contiguous;
    private byte[][] pages;
    private final int totalBytes;

    private RequestByteArena(byte[] contiguous, byte[][] pages, int totalBytes) {
        this.contiguous = contiguous;
        this.pages = pages;
        this.totalBytes = totalBytes;
    }

    /**
     * Releases backing byte arrays held by this arena once parsing has completed.
     *
     * <p>Zero-copy slices inside {@link CanonicalRequest} keep their own direct references to the
     * underlying {@code byte[]} for the duration of the pre-first-byte retry window; clearing the
     * arena ensures no upstream reactive closure (such as {@code Mono.fromCallable}) can keep the
     * raw payload pinned after {@link RequestIrHolder#release()} drops the IR.
     */
    public void clear() {
        if (this == EMPTY) {
            return;
        }
        this.contiguous = null;
        this.pages = null;
    }

    public static RequestByteArena readFrom(DataBuffer dataBuffer) {
        return readFrom(dataBuffer, DEFAULT_CONTIGUOUS_THRESHOLD_BYTES, DEFAULT_PAGE_SIZE_BYTES);
    }

    static RequestByteArena readFrom(DataBuffer dataBuffer, int contiguousThreshold, int pageSize) {
        if (dataBuffer == null) {
            return EMPTY;
        }
        int total = dataBuffer.readableByteCount();
        if (total <= 0) {
            return EMPTY;
        }
        if (total <= contiguousThreshold) {
            byte[] single = new byte[total];
            dataBuffer.read(single);
            return new RequestByteArena(single, null, total);
        }
        int effectivePageSize = Math.max(4096, pageSize);
        int pageCount = (total + effectivePageSize - 1) / effectivePageSize;
        byte[][] pages = new byte[pageCount][];
        int remaining = total;
        for (int i = 0; i < pageCount; i++) {
            int chunk = Math.min(effectivePageSize, remaining);
            byte[] page = new byte[chunk];
            dataBuffer.read(page);
            pages[i] = page;
            remaining -= chunk;
        }
        return new RequestByteArena(null, pages, total);
    }

    public static RequestByteArena wrapContiguous(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return EMPTY;
        }
        return new RequestByteArena(rawBody, null, rawBody.length);
    }

    public int size() {
        return totalBytes;
    }

    public boolean isPaged() {
        return pages != null;
    }

    int pageCount() {
        return pages != null ? pages.length : (contiguous != null ? 1 : 0);
    }

    public CanonicalRequest parse(
            ProtocolTranslator translator,
            ProtocolKind protocolKind,
            InboundRequestContext inboundContext
    ) {
        if (pages != null) {
            return translator.parseRequest(protocolKind, new PagedInputStream(pages), inboundContext);
        }
        return translator.parseRequest(protocolKind, contiguous, 0, totalBytes, inboundContext);
    }

    private static final class PagedInputStream extends InputStream {
        private final byte[][] pages;
        private int pageIdx;
        private int offset;

        private PagedInputStream(byte[][] pages) {
            this.pages = pages;
        }

        @Override
        public int read() {
            while (pageIdx < pages.length) {
                byte[] p = pages[pageIdx];
                if (offset < p.length) {
                    return p[offset++] & 0xFF;
                }
                pageIdx++;
                offset = 0;
            }
            return -1;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            if (len == 0) {
                return 0;
            }
            while (pageIdx < pages.length) {
                byte[] p = pages[pageIdx];
                int avail = p.length - offset;
                if (avail > 0) {
                    int n = Math.min(len, avail);
                    System.arraycopy(p, offset, dst, off, n);
                    offset += n;
                    return n;
                }
                pageIdx++;
                offset = 0;
            }
            return -1;
        }
    }
}
