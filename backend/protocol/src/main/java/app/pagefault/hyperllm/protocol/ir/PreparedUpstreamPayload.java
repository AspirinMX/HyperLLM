package app.pagefault.hyperllm.protocol.ir;

import java.util.List;

/**
 * Outbound upstream request body represented as an ordered sequence of {@link ByteSlice}s so a single
 * request can be assembled from several disjoint spans without materializing a contiguous copy.
 * Every payload is produced by the outbound {@code ProtocolCodec} from Canonical IR; the client's bytes
 * are never forwarded verbatim.
 */
public record PreparedUpstreamPayload(
        ProtocolKind outboundProtocol,
        List<ByteSlice> slices,
        long contentLength
) {
    public PreparedUpstreamPayload {
        slices = slices == null ? List.of() : List.copyOf(slices);
    }

    public static PreparedUpstreamPayload ofSingleArray(ProtocolKind outboundProtocol, byte[] body) {
        byte[] safe = body == null ? new byte[0] : body;
        return new PreparedUpstreamPayload(
                outboundProtocol,
                safe.length == 0 ? List.of() : List.of(ByteSlice.full(safe)),
                safe.length
        );
    }

    /**
     * Materializes the slices into a single contiguous {@code byte[]} (returning the backing array
     * directly without copying when this payload consists of a single full-array slice).
     */
    public byte[] toByteArray() {
        if (slices.isEmpty() || contentLength == 0L) {
            return new byte[0];
        }
        if (slices.size() == 1) {
            ByteSlice only = slices.get(0);
            if (only.offset() == 0 && only.length() == only.backing().length) {
                return only.backing();
            }
        }
        byte[] out = new byte[(int) contentLength];
        int pos = 0;
        for (ByteSlice slice : slices) {
            System.arraycopy(slice.backing(), slice.offset(), out, pos, slice.length());
            pos += slice.length();
        }
        return out;
    }
}
