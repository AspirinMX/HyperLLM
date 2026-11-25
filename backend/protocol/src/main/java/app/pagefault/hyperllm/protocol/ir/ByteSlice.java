package app.pagefault.hyperllm.protocol.ir;

/**
 * Zero-copy view over a contiguous range {@code [offset, offset + length)} of a backing {@code byte[]}.
 */
public record ByteSlice(byte[] backing, int offset, int length) {

    public ByteSlice {
        if (backing == null) {
            throw new IllegalArgumentException("backing must not be null");
        }
        if (offset < 0 || length < 0 || offset + length > backing.length) {
            throw new IndexOutOfBoundsException(
                    "Invalid slice [" + offset + ", " + (offset + length) + ") for length " + backing.length);
        }
    }

    public static ByteSlice full(byte[] bytes) {
        return new ByteSlice(bytes, 0, bytes.length);
    }
}
