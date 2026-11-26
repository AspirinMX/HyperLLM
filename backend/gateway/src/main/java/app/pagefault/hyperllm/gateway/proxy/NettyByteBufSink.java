package app.pagefault.hyperllm.gateway.proxy;

import app.pagefault.hyperllm.protocol.io.ByteSink;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ReferenceCountUtil;

import java.io.OutputStream;

/**
 * {@link ByteSink} backed by a pooled Netty direct {@link ByteBuf}.
 *
 * <p>Protocol encoders serialize JSON directly into pooled off-heap memory, eliminating:
 * <ul>
 *   <li>400 KB+ temporary heap {@code byte[]} allocations and G1 Humongous allocations, and</li>
 *   <li>Netty's heap-to-direct copy before socket {@code writev}.</li>
 * </ul>
 *
 * <p>Ownership contract: callers MUST either invoke {@link #finishAndTransfer()} (transferring the
 * returned {@link ByteBuf}'s reference count to the caller) or {@link #release()} on error/abort.
 */
public final class NettyByteBufSink implements ByteSink {

    private ByteBuf buf;
    private OutputStream outputStreamView;

    public NettyByteBufSink(int initialCapacity) {
        this(PooledByteBufAllocator.DEFAULT, initialCapacity);
    }

    public NettyByteBufSink(ByteBufAllocator allocator, int initialCapacity) {
        ByteBufAllocator alloc = allocator != null ? allocator : PooledByteBufAllocator.DEFAULT;
        this.buf = alloc.directBuffer(Math.max(256, initialCapacity));
    }

    @Override
    public void writeByte(int b) {
        requireOpen().writeByte(b);
    }

    @Override
    public void writeBytes(byte[] src, int offset, int length) {
        if (length <= 0) {
            return;
        }
        requireOpen().writeBytes(src, offset, length);
    }

    @Override
    public OutputStream asOutputStream() {
        if (outputStreamView == null) {
            outputStreamView = new OutputStream() {
                @Override
                public void write(int b) {
                    requireOpen().writeByte(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    if (len > 0) {
                        requireOpen().writeBytes(b, off, len);
                    }
                }

                @Override
                public void close() {
                    // No-op: ByteBuf lifecycle is governed by finishAndTransfer() / release().
                }
            };
        }
        return outputStreamView;
    }

    @Override
    public int writtenBytes() {
        return buf != null ? buf.readableBytes() : 0;
    }

    /**
     * Transfers ownership of the backing {@link ByteBuf} to the caller. Subsequent calls to
     * {@link #release()} become a safe no-op.
     */
    public ByteBuf finishAndTransfer() {
        ByteBuf out = this.buf;
        this.buf = null;
        return out;
    }

    /** Releases the backing {@link ByteBuf} if ownership has not yet been transferred. */
    public void release() {
        ByteBuf remaining = this.buf;
        this.buf = null;
        if (remaining != null) {
            ReferenceCountUtil.safeRelease(remaining);
        }
    }

    private ByteBuf requireOpen() {
        ByteBuf current = this.buf;
        if (current == null) {
            throw new IllegalStateException("NettyByteBufSink has already been transferred or released");
        }
        return current;
    }
}
