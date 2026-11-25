package app.pagefault.hyperllm.protocol.fast;

import app.pagefault.hyperllm.protocol.ir.ByteSlice;
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamPayload;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Zero-copy, zero-re-serialization byte-range splicer for homomorphic protocol passthrough.
 *
 * <p>Uses exact byte spans collected by {@link StrictJsonRequestScanner} to rewrite at most 0–2
 * small fields ({@code model}, {@code stream_options.include_usage}, or default {@code max_tokens})
 * while leaving 99.9%+ of a large agent request body completely untouched in-place.
 */
public final class PassthroughBytePatcher {

    private static final int ANTHROPIC_DEFAULT_MAX_TOKENS = 8192;

    private static final byte[] TRUE_BYTES = "true".getBytes(StandardCharsets.UTF_8);
    private static final byte[] STREAM_OPTIONS_OBJ_BYTES =
            "{\"include_usage\":true}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] STREAM_OPTIONS_FIELD_WITH_COMMA =
            ",\"stream_options\":{\"include_usage\":true}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] STREAM_OPTIONS_FIELD_NO_COMMA =
            "\"stream_options\":{\"include_usage\":true}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INCLUDE_USAGE_FIELD_WITH_COMMA =
            ",\"include_usage\":true".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INCLUDE_USAGE_FIELD_NO_COMMA =
            "\"include_usage\":true".getBytes(StandardCharsets.UTF_8);

    private record Patch(int start, int end, byte[] replacement) {
    }

    private PassthroughBytePatcher() {
    }

    public static PreparedUpstreamPayload patch(
            byte[] rawBody,
            FastRequestProbe probe,
            UpstreamEncodingContext context
    ) {
        ProtocolKind protocol = probe.inboundProtocol();
        if (protocol == ProtocolKind.GEMINI_GENERATE_CONTENT) {
            // Model and stream action live in the Gemini URL path; body passes through unmodified.
            return PreparedUpstreamPayload.ofSingleArray(protocol, rawBody, true);
        }

        Patch p1 = null;
        Patch p2 = null;

        String upstreamModel = context != null ? context.upstreamModelName() : null;
        if (upstreamModel != null && !upstreamModel.isBlank()
                && probe.modelValStart() >= 0 && probe.modelValEnd() > probe.modelValStart()) {
            byte[] quotedUpstream = jsonQuoteUtf8(upstreamModel);
            if (!sliceEquals(rawBody, probe.modelValStart(), probe.modelValEnd(), quotedUpstream)) {
                p1 = new Patch(probe.modelValStart(), probe.modelValEnd(), quotedUpstream);
            }
        }

        if (protocol == ProtocolKind.OPENAI_CHAT && probe.stream() && !probe.clientRequestedUsage()) {
            Patch usagePatch = buildOpenAiIncludeUsagePatch(probe);
            if (p1 == null) {
                p1 = usagePatch;
            } else {
                p2 = usagePatch;
            }
        } else if (protocol == ProtocolKind.ANTHROPIC_MESSAGES && !probe.hasNonNullMaxTokens()) {
            int defaultMax = context != null
                    ? context.resolveDefaultMaxOutputTokens(ANTHROPIC_DEFAULT_MAX_TOKENS)
                    : ANTHROPIC_DEFAULT_MAX_TOKENS;
            Patch maxTokensPatch = buildAnthropicMaxTokensPatch(probe, defaultMax);
            if (p1 == null) {
                p1 = maxTokensPatch;
            } else {
                p2 = maxTokensPatch;
            }
        }

        if (p1 == null) {
            return PreparedUpstreamPayload.ofSingleArray(protocol, rawBody, true);
        }
        if (p2 == null) {
            return spliceOne(protocol, rawBody, p1);
        }
        if (p2.start() < p1.start()) {
            Patch tmp = p1;
            p1 = p2;
            p2 = tmp;
        }
        return spliceTwo(protocol, rawBody, p1, p2);
    }

    private static Patch buildOpenAiIncludeUsagePatch(FastRequestProbe probe) {
        if (probe.streamOptionsStart() < 0) {
            byte[] insert = probe.rootHasFields()
                    ? STREAM_OPTIONS_FIELD_WITH_COMMA
                    : STREAM_OPTIONS_FIELD_NO_COMMA;
            return new Patch(probe.rootCloseBrace(), probe.rootCloseBrace(), insert);
        }
        if (!probe.streamOptionsIsObject()) {
            return new Patch(probe.streamOptionsStart(), probe.streamOptionsEnd(), STREAM_OPTIONS_OBJ_BYTES);
        }
        if (probe.includeUsageValStart() >= 0) {
            return new Patch(probe.includeUsageValStart(), probe.includeUsageValEnd(), TRUE_BYTES);
        }
        int insertAt = probe.streamOptionsEnd() - 1; // immediately before closing '}' of stream_options
        byte[] insert = probe.streamOptionsHasFields()
                ? INCLUDE_USAGE_FIELD_WITH_COMMA
                : INCLUDE_USAGE_FIELD_NO_COMMA;
        return new Patch(insertAt, insertAt, insert);
    }

    private static Patch buildAnthropicMaxTokensPatch(FastRequestProbe probe, int defaultMaxTokens) {
        if (probe.maxTokensValStart() >= 0 && probe.maxTokensValEnd() > probe.maxTokensValStart()) {
            // Replace existing "max_tokens": null in-place
            byte[] valBytes = Integer.toString(defaultMaxTokens).getBytes(StandardCharsets.UTF_8);
            return new Patch(probe.maxTokensValStart(), probe.maxTokensValEnd(), valBytes);
        }
        String field = (probe.rootHasFields() ? "," : "") + "\"max_tokens\":" + defaultMaxTokens;
        return new Patch(probe.rootCloseBrace(), probe.rootCloseBrace(), field.getBytes(StandardCharsets.UTF_8));
    }

    private static PreparedUpstreamPayload spliceOne(ProtocolKind protocol, byte[] raw, Patch p) {
        List<ByteSlice> slices = new ArrayList<>(3);
        if (p.start() > 0) {
            slices.add(new ByteSlice(raw, 0, p.start()));
        }
        if (p.replacement().length > 0) {
            slices.add(ByteSlice.full(p.replacement()));
        }
        if (p.end() < raw.length) {
            slices.add(new ByteSlice(raw, p.end(), raw.length - p.end()));
        }
        long contentLength = (long) raw.length + p.replacement().length - (p.end() - p.start());
        return new PreparedUpstreamPayload(protocol, slices, contentLength, true);
    }

    private static PreparedUpstreamPayload spliceTwo(ProtocolKind protocol, byte[] raw, Patch p1, Patch p2) {
        List<ByteSlice> slices = new ArrayList<>(5);
        if (p1.start() > 0) {
            slices.add(new ByteSlice(raw, 0, p1.start()));
        }
        if (p1.replacement().length > 0) {
            slices.add(ByteSlice.full(p1.replacement()));
        }
        if (p2.start() > p1.end()) {
            slices.add(new ByteSlice(raw, p1.end(), p2.start() - p1.end()));
        }
        if (p2.replacement().length > 0) {
            slices.add(ByteSlice.full(p2.replacement()));
        }
        if (p2.end() < raw.length) {
            slices.add(new ByteSlice(raw, p2.end(), raw.length - p2.end()));
        }
        long contentLength = (long) raw.length
                + p1.replacement().length - (p1.end() - p1.start())
                + p2.replacement().length - (p2.end() - p2.start());
        return new PreparedUpstreamPayload(protocol, slices, contentLength, true);
    }

    private static boolean sliceEquals(byte[] raw, int start, int end, byte[] expected) {
        if (end - start != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (raw[start + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] jsonQuoteUtf8(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 4);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
