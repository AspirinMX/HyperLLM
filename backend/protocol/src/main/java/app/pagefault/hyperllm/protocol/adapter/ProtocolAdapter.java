package app.pagefault.hyperllm.protocol.adapter;

import app.pagefault.hyperllm.protocol.ir.CanonicalChatRequest;
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamRequest;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;

import java.util.Optional;

/**
 * SPI for parsing, preparing, and extracting usage telemetry for a wire protocol family.
 *
 * <p>Phase 1 validates the wire body and rewrites the model name before handing it to the codec layer.
 * Phase 3 will layer heterogeneous request/response translation onto this SPI.
 */
public interface ProtocolAdapter {

    ProtocolKind kind();

    CanonicalChatRequest parseRequest(byte[] rawBody);

    PreparedUpstreamRequest prepareUpstreamRequest(byte[] rawBody, String upstreamModelName);

    Optional<TokenUsage> extractNonStreamingUsage(byte[] responseBody);

    StreamUsageTracker newStreamTracker(CanonicalChatRequest request);
}
