package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.protocol.io.ByteSink;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalResponse;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamPayload;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.StreamRequestMetadata;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;

import java.io.InputStream;
import java.util.Optional;

/**
 * Bidirectional codec SPI for one of the four supported text-generation wire protocols.
 */
public interface ProtocolCodec {

    ProtocolKind kind();

    CanonicalRequest decodeRequest(byte[] rawBody, InboundRequestContext context);

    default CanonicalRequest decodeRequest(
            byte[] rawBody,
            int offset,
            int length,
            InboundRequestContext context
    ) {
        if (rawBody != null && offset == 0 && length == rawBody.length) {
            return decodeRequest(rawBody, context);
        }
        byte[] copy = (rawBody == null || length <= 0)
                ? new byte[0]
                : java.util.Arrays.copyOfRange(rawBody, offset, offset + length);
        return decodeRequest(copy, context);
    }

    /**
     * Decodes a request directly from a streaming {@link InputStream} (used for paged arenas on
     * multi-megabyte payloads to avoid G1 Humongous contiguous {@code byte[]} allocations).
     */
    CanonicalRequest decodeRequest(InputStream bodyStream, InboundRequestContext context);

    /**
     * Encodes the normalized Canonical IR directly into a caller-supplied {@link ByteSink}
     * (e.g. a pooled Netty direct {@code ByteBuf} sink or a {@link ByteSink.HeapByteSink}).
     */
    void encodeRequest(CanonicalRequest normalizedRequest, UpstreamEncodingContext context, ByteSink out);

    default byte[] encodeRequest(CanonicalRequest normalizedRequest, UpstreamEncodingContext context) {
        ByteSink.HeapByteSink sink = new ByteSink.HeapByteSink(CodecSupport.estimateEncodedCapacity(normalizedRequest));
        encodeRequest(normalizedRequest, context, sink);
        return sink.toExactByteArray();
    }

    /**
     * Same as {@link #encodeRequest} but returns the body as a zero-copy slice over the serialization
     * buffer without copying it into an exactly-sized array.
     */
    default PreparedUpstreamPayload encodeRequestPayload(
            CanonicalRequest normalizedRequest,
            UpstreamEncodingContext context
    ) {
        ByteSink.HeapByteSink sink = new ByteSink.HeapByteSink(CodecSupport.estimateEncodedCapacity(normalizedRequest));
        encodeRequest(normalizedRequest, context, sink);
        return PreparedUpstreamPayload.ofSlice(kind(), sink.backingArray(), sink.writtenBytes());
    }

    CanonicalResponse decodeResponse(byte[] responseBody, String virtualModel);

    byte[] encodeResponse(CanonicalResponse response);

    Optional<TokenUsage> extractNonStreamingUsage(byte[] responseBody);

    UpstreamStreamDecoder newStreamDecoder(String virtualModel);

    DownstreamStreamEncoder newStreamEncoder(StreamRequestMetadata inboundMeta);

    default DownstreamStreamEncoder newStreamEncoder(CanonicalRequest inboundRequest) {
        return newStreamEncoder(StreamRequestMetadata.fromCanonical(inboundRequest, 0));
    }
}
