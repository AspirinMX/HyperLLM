package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalResponse;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.StreamRequestMetadata;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;

import java.util.Optional;

/**
 * Bidirectional codec SPI for one of the four supported text-generation wire protocols.
 */
public interface ProtocolCodec {

    ProtocolKind kind();

    CanonicalRequest decodeRequest(byte[] rawBody, InboundRequestContext context);

    byte[] encodeRequest(CanonicalRequest normalizedRequest, UpstreamEncodingContext context);

    CanonicalResponse decodeResponse(byte[] responseBody, String virtualModel);

    byte[] encodeResponse(CanonicalResponse response);

    Optional<TokenUsage> extractNonStreamingUsage(byte[] responseBody);

    UpstreamStreamDecoder newStreamDecoder(String virtualModel);

    DownstreamStreamEncoder newStreamEncoder(StreamRequestMetadata inboundMeta);

    default DownstreamStreamEncoder newStreamEncoder(CanonicalRequest inboundRequest) {
        return newStreamEncoder(StreamRequestMetadata.fromCanonical(inboundRequest, 0));
    }
}
