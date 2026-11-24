package app.pagefault.hyperllm.protocol.tokenizer;

import app.pagefault.hyperllm.protocol.ir.CanonicalChatRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalContentPart;
import app.pagefault.hyperllm.protocol.ir.CanonicalMessage;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalSystemPart;
import app.pagefault.hyperllm.protocol.ir.CanonicalToolDefinition;
import app.pagefault.hyperllm.protocol.ir.CanonicalTurn;
import app.pagefault.hyperllm.protocol.ir.ImageDetail;
import app.pagefault.hyperllm.protocol.ir.ImageSource;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import java.util.Locale;

/**
 * Local multimodal token estimator powered by jtokkit and lightweight image header probing.
 * Used for Pre-hold estimation and usage fallback when upstream omits telemetry or disconnects mid-stream.
 */
public final class TokenEstimator {

    private static final int TOKENS_PER_MESSAGE_OVERHEAD = 3;
    private static final int REPLY_PRIMING_TOKENS = 3;
    private static final int LOW_DETAIL_IMAGE_TOKENS = 85;
    private static final int DEFAULT_IMAGE_TOKENS = 765;
    private static final int ORIGINAL_IMAGE_TOKENS = 1_105;
    private static final int TOKENS_PER_512_TILE = 170;

    private final EncodingRegistry registry = Encodings.newLazyEncodingRegistry();

    public int countText(String model, CharSequence text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return resolveEncoding(model).countTokensOrdinary(text.toString());
    }

    /**
     * Estimates total prompt tokens across system instructions, multimodal turns (text, images,
     * tool calls/results, thinking blocks), and tool schema definitions.
     */
    public int estimatePromptTokens(CanonicalRequest request) {
        if (request == null) {
            return 0;
        }
        Encoding encoding = resolveEncoding(request.model());
        int total = REPLY_PRIMING_TOKENS;

        for (CanonicalSystemPart sys : request.systemInstructions()) {
            total += TOKENS_PER_MESSAGE_OVERHEAD;
            if (!sys.text().isEmpty()) {
                total += encoding.countTokensOrdinary(sys.text());
            }
        }

        for (CanonicalTurn turn : request.turns()) {
            total += TOKENS_PER_MESSAGE_OVERHEAD;
            if (turn.role() != null) {
                total += encoding.countTokensOrdinary(turn.role().name().toLowerCase(Locale.ROOT));
            }
            for (CanonicalContentPart part : turn.parts()) {
                total += estimatePartTokens(encoding, part);
            }
        }

        for (CanonicalToolDefinition tool : request.tools()) {
            total += 8; // structural tool overhead
            if (tool.name() != null && !tool.name().isEmpty()) {
                total += encoding.countTokensOrdinary(tool.name());
            }
            if (tool.description() != null && !tool.description().isEmpty()) {
                total += encoding.countTokensOrdinary(tool.description());
            }
            if (tool.parametersSchema() != null && !tool.parametersSchema().isNull()) {
                total += encoding.countTokensOrdinary(tool.parametersSchema().toString());
            }
        }

        return total;
    }

    /** Backwards-compatible estimator for legacy {@link CanonicalChatRequest}. */
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

    private int estimatePartTokens(Encoding encoding, CanonicalContentPart part) {
        return switch (part) {
            case CanonicalContentPart.TextPart t ->
                    t.text().isEmpty() ? 0 : encoding.countTokensOrdinary(t.text());
            case CanonicalContentPart.ImagePart img -> estimateImageTokens(img);
            case CanonicalContentPart.ThinkingPart th ->
                    th.text().isEmpty() ? 0 : encoding.countTokensOrdinary(th.text());
            case CanonicalContentPart.RedactedThinkingPart ignored -> 64;
            case CanonicalContentPart.RefusalPart ref ->
                    ref.refusal().isEmpty() ? 0 : encoding.countTokensOrdinary(ref.refusal());
            case CanonicalContentPart.ToolCallPart tc -> {
                int sum = 6;
                if (tc.name() != null && !tc.name().isEmpty()) {
                    sum += encoding.countTokensOrdinary(tc.name());
                }
                String args = tc.rawArgumentsJson() != null
                        ? tc.rawArgumentsJson()
                        : (tc.argumentsJson() != null ? tc.argumentsJson().toString() : "");
                if (!args.isEmpty()) {
                    sum += encoding.countTokensOrdinary(args);
                }
                yield sum;
            }
            case CanonicalContentPart.ToolResultPart tr -> {
                int sum = 4;
                for (CanonicalContentPart child : tr.content()) {
                    sum += estimatePartTokens(encoding, child);
                }
                yield sum;
            }
        };
    }

    public static int estimateImageTokens(CanonicalContentPart.ImagePart imagePart) {
        if (imagePart == null) {
            return 0;
        }
        ImageDetail detail = imagePart.detail() != null ? imagePart.detail() : ImageDetail.AUTO;
        if (detail == ImageDetail.LOW) {
            return LOW_DETAIL_IMAGE_TOKENS;
        }
        if (imagePart.source() instanceof ImageSource.Base64Image b64) {
            return ImageDimensionProbe.probeBase64(b64.base64Data())
                    .map(dim -> {
                        int clampedW = Math.min(dim.width(), 2048);
                        int clampedH = Math.min(dim.height(), 2048);
                        int tilesX = (clampedW + 511) / 512;
                        int tilesY = (clampedH + 511) / 512;
                        return tilesX * tilesY * TOKENS_PER_512_TILE + LOW_DETAIL_IMAGE_TOKENS;
                    })
                    .orElse(detail == ImageDetail.ORIGINAL ? ORIGINAL_IMAGE_TOKENS : DEFAULT_IMAGE_TOKENS);
        }
        return detail == ImageDetail.ORIGINAL ? ORIGINAL_IMAGE_TOKENS : DEFAULT_IMAGE_TOKENS;
    }

    Encoding resolveEncoding(String model) {
        if (model != null && !model.isBlank()) {
            String normalized = model.trim().toLowerCase(Locale.ROOT);
            var matched = registry.getEncodingForModel(normalized);
            if (matched.isPresent()) {
                return matched.get();
            }
            if (normalized.startsWith("gpt-4o")
                    || normalized.startsWith("gpt-4.1")
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
