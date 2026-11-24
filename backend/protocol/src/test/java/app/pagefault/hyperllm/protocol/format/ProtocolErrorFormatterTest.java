package app.pagefault.hyperllm.protocol.format;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;

class ProtocolErrorFormatterTest {

    private final ProtocolErrorFormatter formatter = new ProtocolErrorFormatter(new ObjectMapper());

    @Test
    void formatsOpenAiAndAnthropicErrorEnvelopes() throws Exception {
        String openaiJson = formatter.formatErrorJson(
                ProtocolKind.OPENAI_CHAT,
                ErrorCode.BILLING_INSUFFICIENT_BALANCE,
                "Insufficient quota for this organization."
        );
        JSONAssert.assertEquals("""
                {
                  "error": {
                    "message": "Insufficient quota for this organization.",
                    "type": "insufficient_quota",
                    "param": null,
                    "code": "BILLING_INSUFFICIENT_BALANCE"
                  }
                }
                """, openaiJson, true);

        String anthropicJson = formatter.formatErrorJson(
                ProtocolKind.ANTHROPIC_MESSAGES,
                ErrorCode.RATE_LIMITED,
                "Rate limit exceeded for API key."
        );
        JSONAssert.assertEquals("""
                {
                  "type": "error",
                  "error": {
                    "type": "rate_limit_error",
                    "message": "Rate limit exceeded for API key."
                  }
                }
                """, anthropicJson, true);

        String geminiJson = formatter.formatErrorJson(
                ProtocolKind.GEMINI_GENERATE_CONTENT,
                ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                "previous_response_id is unsupported"
        );
        JSONAssert.assertEquals("""
                {
                  "error": {
                    "code": 400,
                    "message": "previous_response_id is unsupported",
                    "status": "INVALID_ARGUMENT"
                  }
                }
                """, geminiJson, true);

        byte[] translated = formatter.translateUpstreamError(
                ProtocolKind.ANTHROPIC_MESSAGES,
                ProtocolKind.GEMINI_GENERATE_CONTENT,
                400,
                "{\"error\":{\"code\":400,\"message\":\"Invalid argument in contents\",\"status\":\"INVALID_ARGUMENT\"}}".getBytes()
        );
        JSONAssert.assertEquals("""
                {
                  "type": "error",
                  "error": {
                    "type": "invalid_request_error",
                    "message": "Invalid argument in contents"
                  }
                }
                """, new String(translated), true);
    }
}
