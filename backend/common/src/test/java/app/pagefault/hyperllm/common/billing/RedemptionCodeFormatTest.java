package app.pagefault.hyperllm.common.billing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedemptionCodeFormatTest {

    @Test
    void normalizesPresentationButKeepsTheIdentity() {
        assertThat(RedemptionCodeFormat.normalize(" hl-abcd-efgh-jk23 ")).isEqualTo("HLABCDEFGHJK23");
        assertThat(RedemptionCodeFormat.hash("HLABCDEFGHJK23", "pepper"))
                .isEqualTo(RedemptionCodeFormat.hash(RedemptionCodeFormat.normalize("hl abcd efgh jk23"), "pepper"));
    }

    @Test
    void generatesCodesWithTheDocumentedShape() {
        String code = RedemptionCodeFormat.generate();
        assertThat(code).matches("HL-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(RedemptionCodeFormat.hint(code)).startsWith("****");
    }
}
