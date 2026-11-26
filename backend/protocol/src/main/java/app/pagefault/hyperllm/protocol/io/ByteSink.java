package app.pagefault.hyperllm.protocol.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Pure-Java zero-copy output target for protocol encoders.
 *
 * <p>Keeping this SPI free of Netty/Spring types preserves {@code hyperllm-protocol}'s lightweight
 * module boundary while allowing {@code hyperllm-gateway} to back it with a pooled direct
 * {@code ByteBuf} (zero heap copies, zero G1 Humongous allocations, zero heap-to-direct copies).
 */
public interface ByteSink {

    void writeByte(int b);

    void writeBytes(byte[] src, int offset, int length);

    default void writeBytes(byte[] src) {
        if (src != null && src.length > 0) {
            writeBytes(src, 0, src.length);
        }
    }

    /** Writes a non-negative integer as ASCII digits without allocating a temporary {@link String}. */
    default void writeAsciiInt(int value) {
        if (value == 0) {
            writeByte('0');
            return;
        }
        if (value > 0 && value < 10) {
            writeByte('0' + value);
            return;
        }
        if (value == Integer.MIN_VALUE) {
            writeBytes(MIN_INT_BYTES, 0, MIN_INT_BYTES.length);
            return;
        }
        int v = value;
        if (v < 0) {
            writeByte('-');
            v = -v;
        }
        byte[] digits = new byte[10];
        int pos = digits.length;
        while (v > 0) {
            int q = v / 10;
            digits[--pos] = (byte) ('0' + (v - q * 10));
            v = q;
        }
        writeBytes(digits, pos, digits.length - pos);
    }

    /** Writes an {@code OutputStream} view directly onto this sink (for Jackson {@code JsonGenerator}). */
    OutputStream asOutputStream();

    /** Total number of bytes written since creation or last reset. */
    int writtenBytes();

    byte[] MIN_INT_BYTES = "-2147483648".getBytes(StandardCharsets.US_ASCII);

    /**
     * Growable heap-backed {@link ByteSink} used by tests, standalone callers, and reusable frame
     * buffers when no pooled direct buffer is supplied.
     */
    final class HeapByteSink extends OutputStream implements ByteSink {

        private byte[] buf;
        private int count;

        public HeapByteSink(int initialCapacity) {
            this.buf = new byte[Math.max(256, initialCapacity)];
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity > buf.length) {
                int newCap = Math.max(minCapacity, buf.length << 1);
                buf = Arrays.copyOf(buf, newCap);
            }
        }

        @Override
        public void writeByte(int b) {
            ensureCapacity(count + 1);
            buf[count++] = (byte) b;
        }

        @Override
        public void writeBytes(byte[] src, int offset, int length) {
            if (length <= 0) {
                return;
            }
            ensureCapacity(count + length);
            System.arraycopy(src, offset, buf, count, length);
            count += length;
        }

        @Override
        public void write(int b) {
            writeByte(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            writeBytes(b, off, len);
        }

        @Override
        public OutputStream asOutputStream() {
            return this;
        }

        @Override
        public int writtenBytes() {
            return count;
        }

        public void reset() {
            count = 0;
        }

        public byte[] backingArray() {
            return buf;
        }

        public byte[] toExactByteArray() {
            return count == buf.length ? buf : Arrays.copyOf(buf, count);
        }

        public byte[] toCopiedByteArray() {
            return Arrays.copyOf(buf, count);
        }

        @Override
        public void close() throws IOException {
            // No-op: backing array remains readable after JsonGenerator closes.
        }
    }
}
