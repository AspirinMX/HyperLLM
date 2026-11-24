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

import java.util.Locale;

/**
 * Local multimodal token estimator based on text length and lightweight image header probing.
 * Used for Pre-hold estimation and usage fallback when upstream omits telemetry or disconnects mid-stream.
 */
public final class TokenEstimator {

    private static final int TOKENS_PER_MESSAGE_OVERHEAD = 3;
    private static final int REPLY_PRIMING_TOKENS = 3;
    private static final int LOW_DETAIL_IMAGE_TOKENS = 85;
    private static final int DEFAULT_IMAGE_TOKENS = 765;
    private static final int ORIGINAL_IMAGE_TOKENS = 1_105;
    private static final int TOKENS_PER_512_TILE = 170;

    /** Estimates text tokens independently of the model. */
    public int countText(String model, CharSequence text) {
        return estimateTextTokens(text);
    }

    private static int estimateTextTokens(CharSequence text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int codePoints = 0;
        long utf8Bytes = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = Character.codePointAt(text, offset);
            offset += Character.charCount(codePoint);
            codePoints++;
            utf8Bytes += utf8Width(codePoint);
        }

        // Keep the larger estimate so the byte count gives multibyte text more weight.
        long byBytes = (utf8Bytes + 3) / 4;
        long byCharacters = (codePoints + 2L) / 3;
        return (int) Math.max(byBytes, byCharacters);
    }

    private static int utf8Width(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (Character.isSupplementaryCodePoint(codePoint)) {
            return 4;
        }
        // Java's UTF-8 encoder replaces an unpaired surrogate with a single byte.
        return Character.isSurrogate((char) codePoint) ? 1 : 3;
    }

    /**
     * Estimates total prompt tokens across system instructions, multimodal turns (text, images,
     * tool calls/results, thinking blocks), and tool schema definitions.
     */
    public int estimatePromptTokens(CanonicalRequest request) {
        if (request == null) {
            return 0;
        }
        int total = REPLY_PRIMING_TOKENS;

        for (CanonicalSystemPart sys : request.systemInstructions()) {
            total += TOKENS_PER_MESSAGE_OVERHEAD;
            if (!sys.text().isEmpty()) {
                total += estimateTextTokens(sys.text());
            }
        }

        for (CanonicalTurn turn : request.turns()) {
            total += TOKENS_PER_MESSAGE_OVERHEAD;
            if (turn.role() != null) {
                total += estimateTextTokens(turn.role().name().toLowerCase(Locale.ROOT));
            }
            for (CanonicalContentPart part : turn.parts()) {
                total += estimatePartTokens(part);
            }
        }

        for (CanonicalToolDefinition tool : request.tools()) {
            total += 8; // structural tool overhead
            if (tool.name() != null && !tool.name().isEmpty()) {
                total += estimateTextTokens(tool.name());
            }
            if (tool.description() != null && !tool.description().isEmpty()) {
                total += estimateTextTokens(tool.description());
            }
            if (tool.parametersSchema() != null && !tool.parametersSchema().isNull()) {
                total += estimateTextTokens(tool.parametersSchema().toString());
            }
        }

        return total;
    }

    /** Backwards-compatible estimator for legacy {@link CanonicalChatRequest}. */
    public int estimatePromptTokens(CanonicalChatRequest request) {
        if (request == null) {
            return 0;
        }
        if (request.messages() != null && !request.messages().isEmpty()) {
            int total = REPLY_PRIMING_TOKENS;
            for (CanonicalMessage msg : request.messages()) {
                total += TOKENS_PER_MESSAGE_OVERHEAD;
                if (msg.role() != null && !msg.role().isEmpty()) {
                    total += estimateTextTokens(msg.role());
                }
                if (msg.content() != null && !msg.content().isEmpty()) {
                    total += estimateTextTokens(msg.content());
                }
            }
            return total;
        }
        return estimateTextTokens(request.rawPromptText());
    }

    public int estimateCompletionTokens(String model, CharSequence streamedText) {
        return estimateTextTokens(streamedText);
    }

    private static int estimatePartTokens(CanonicalContentPart part) {
        return switch (part) {
            case CanonicalContentPart.TextPart t ->
                    t.text().isEmpty() ? 0 : estimateTextTokens(t.text());
            case CanonicalContentPart.ImagePart img -> estimateImageTokens(img);
            case CanonicalContentPart.ThinkingPart th ->
                    th.text().isEmpty() ? 0 : estimateTextTokens(th.text());
            case CanonicalContentPart.RedactedThinkingPart ignored -> 64;
            case CanonicalContentPart.RefusalPart ref ->
                    ref.refusal().isEmpty() ? 0 : estimateTextTokens(ref.refusal());
            case CanonicalContentPart.ToolCallPart tc -> {
                int sum = 6;
                if (tc.name() != null && !tc.name().isEmpty()) {
                    sum += estimateTextTokens(tc.name());
                }
                String args = tc.rawArgumentsJson() != null
                        ? tc.rawArgumentsJson()
                        : (tc.argumentsJson() != null ? tc.argumentsJson().toString() : "");
                if (!args.isEmpty()) {
                    sum += estimateTextTokens(args);
                }
                yield sum;
            }
            case CanonicalContentPart.ToolResultPart tr -> {
                int sum = 4;
                for (CanonicalContentPart child : tr.content()) {
                    sum += estimatePartTokens(child);
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
}
