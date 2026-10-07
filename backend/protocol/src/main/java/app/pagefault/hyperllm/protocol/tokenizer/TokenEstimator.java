package app.pagefault.hyperllm.protocol.tokenizer;

import app.pagefault.hyperllm.protocol.ir.CanonicalChatRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalMessage;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import java.util.Locale;

/**
 * Local token estimator powered by jtokkit for usage fallback when upstream omits telemetry
 * or a streaming response terminates mid-flight.
 */
public final class TokenEstimator {

    private static final int TOKENS_PER_MESSAGE_OVERHEAD = 3;
    private static final int REPLY_PRIMING_TOKENS = 3;

    private final EncodingRegistry registry = Encodings.newLazyEncodingRegistry();

    public int countText(String model, CharSequence text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return resolveEncoding(model).countTokensOrdinary(text.toString());
    }

    public int estimatePromptTokens(CanonicalChatRequest request) {
        if (request == null) {
            return 0;
        }
        Encoding encoding = resolveEncoding(request.model());
        if (request.messages() != null && !request.messages().isEmpty()) {
            int total = REPLY_PRIMING_TOKENS;
            for (CanonicalMessage msg : request.messages()) {
                total += TOKENS_PER_MESSAGE_OVERHEAD;
                if (msg.role() != null && !msg.role().isEmpty()) {
                    total += encoding.countTokensOrdinary(msg.role());
                }
                if (msg.content() != null && !msg.content().isEmpty()) {
                    total += encoding.countTokensOrdinary(msg.content());
                }
            }
            return total;
        }
        return countText(request.model(), request.rawPromptText());
    }

    public int estimateCompletionTokens(String model, CharSequence streamedText) {
        return countText(model, streamedText);
    }

    Encoding resolveEncoding(String model) {
        if (model != null && !model.isBlank()) {
            String normalized = model.trim().toLowerCase(Locale.ROOT);
            var matched = registry.getEncodingForModel(normalized);
            if (matched.isPresent()) {
                return matched.get();
            }
            if (normalized.startsWith("gpt-4o")
                    || normalized.startsWith("gpt-5")
                    || normalized.startsWith("o1")
                    || normalized.startsWith("o3")
                    || normalized.startsWith("o4")) {
                return registry.getEncoding(EncodingType.O200K_BASE);
            }
        }
        return registry.getEncoding(EncodingType.CL100K_BASE);
    }
}
