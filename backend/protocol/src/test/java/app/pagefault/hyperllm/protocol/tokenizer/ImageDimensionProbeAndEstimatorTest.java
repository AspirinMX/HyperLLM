package app.pagefault.hyperllm.protocol.tokenizer;

import app.pagefault.hyperllm.protocol.ir.CanonicalContentPart;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalRole;
import app.pagefault.hyperllm.protocol.ir.CanonicalToolDefinition;
import app.pagefault.hyperllm.protocol.ir.CanonicalTurn;
import app.pagefault.hyperllm.protocol.ir.ImageDetail;
import app.pagefault.hyperllm.protocol.ir.ImageSource;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageDimensionProbeAndEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void estimatesTextFromUtf8BytesAndUnicodeCodePoints() {
        assertThat(estimator.countText(null, null)).isZero();
        assertThat(estimator.countText(null, "")).isZero();
        assertThat(estimator.countText("gpt-4o", "abcdefghij")).isEqualTo(4);
        assertThat(estimator.countText("claude", "abcdefghij")).isEqualTo(4);
        assertThat(estimator.countText(null, "éééé")).isEqualTo(2);
        assertThat(estimator.countText(null, "你好世界")).isEqualTo(3);
        assertThat(estimator.estimateCompletionTokens("gpt-4o", new StringBuilder("😀😀😀"))).isEqualTo(3);
        assertThat(estimator.countText(null, "😀abcdefghijk")).isEqualTo(4);
        assertThat(estimator.countText(null, "\uD800".repeat(3))).isEqualTo(1);
    }

    @Test
    void probesPngAndGifDimensionsWithoutPixelDecoding() {
        // Minimal PNG header (1024 x 768)
        ByteBuffer png = ByteBuffer.allocate(24);
        png.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        png.putInt(13); // IHDR length
        png.put(new byte[]{'I', 'H', 'D', 'R'});
        png.putInt(1024);
        png.putInt(768);
        String pngB64 = Base64.getEncoder().encodeToString(png.array());
        assertThat(ImageDimensionProbe.probeBase64(pngB64))
                .contains(new ImageDimensionProbe.Dimensions(1024, 768));

        // Minimal GIF89a header (640 x 480 little-endian)
        byte[] gif = new byte[]{'G', 'I', 'F', '8', '9', 'a', (byte) 0x80, 0x02, (byte) 0xE0, 0x01};
        String gifB64 = Base64.getEncoder().encodeToString(gif);
        assertThat(ImageDimensionProbe.probeBase64(gifB64))
                .contains(new ImageDimensionProbe.Dimensions(640, 480));
    }

    @Test
    void accountsForImagesAndToolSchemasInMultimodalPromptEstimation() throws Exception {
        ByteBuffer png = ByteBuffer.allocate(24);
        png.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        png.putInt(13);
        png.put(new byte[]{'I', 'H', 'D', 'R'});
        png.putInt(1024);
        png.putInt(1024); // 2x2 tiles -> 4*170 + 85 = 765 tokens
        String pngB64 = Base64.getEncoder().encodeToString(png.array());

        CanonicalRequest textOnly = new CanonicalRequest(
                ProtocolKind.OPENAI_CHAT, "gpt-4o", false, List.of(),
                List.of(new CanonicalTurn(CanonicalRole.USER, List.of(new CanonicalContentPart.TextPart("Describe")))),
                List.of(), null, null, null, null, null, null, List.of()
        );
        CanonicalRequest withImageAndTool = new CanonicalRequest(
                ProtocolKind.OPENAI_CHAT, "gpt-4o", false, List.of(),
                List.of(new CanonicalTurn(CanonicalRole.USER, List.of(
                        new CanonicalContentPart.TextPart("Describe"),
                        new CanonicalContentPart.ImagePart(new ImageSource.Base64Image("image/png", pngB64), ImageDetail.HIGH, null),
                        new CanonicalContentPart.ImagePart(new ImageSource.UrlImage("https://example.com/a.jpg", "image/jpeg"), ImageDetail.LOW, null)
                ))),
                List.of(new CanonicalToolDefinition(
                        "lookup_item", "Finds catalog items by SKU",
                        mapper.readTree("{\"type\":\"object\",\"properties\":{\"sku\":{\"type\":\"string\"}}}"),
                        true, null)),
                null, null, null, null, null, null, List.of()
        );

        int baseTokens = estimator.estimatePromptTokens(textOnly);
        int multiTokens = estimator.estimatePromptTokens(withImageAndTool);

        // 765 (high-detail 1024x1024 PNG) + 85 (low-detail URL) + tool schema tokens
        assertThat(multiTokens - baseTokens).isGreaterThan(850);
    }

    @Test
    void incrementalTextTokenCounterMatchesFullStringEstimationAcrossChunkSplits() {
        String[] samples = {
                "abcdefghij",
                "éééé",
                "你好世界，HyperLLM 流式计费测试！",
                "😀😀😀😀abcdefghijk",
                "\uD800\uD800\uD800",
                "mixed ASCII + 中文 + 🚀🔥 + \uD83D\uDE00 + trailing".repeat(2500) // >100k chars (beyond old 65,536 cap)
        };
        for (String sample : samples) {
            TokenEstimator.TextTokenCounter counter = new TokenEstimator.TextTokenCounter();
            // Feed 1 char at a time so every supplementary surrogate pair is split across calls
            for (int i = 0; i < sample.length(); i++) {
                counter.add(sample.substring(i, i + 1));
            }
            assertThat(counter.estimateTokens())
                    .as("Incremental token estimate for length=%d", sample.length())
                    .isEqualTo(estimator.countText("gpt-4o", sample));
        }
    }
}
