package app.pagefault.hyperllm.protocol.fast;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Single-pass, zero-DOM, strict RFC 8259 UTF-8 byte scanner for inbound LLM requests.
 *
 * <p>Performs all three gateway admission tasks in one linear scan (~0.15–0.30 ms on a 500 KB /
 * 100K-token coding-agent payload with near-zero heap allocation):
 * <ol>
 *   <li>Strict RFC 8259 JSON syntax + UTF-8 + duplicate critical top-level key validation
 *       (preventing malformed 400 requests and duplicate-key smuggling from ever reaching upstreams).</li>
 *   <li>Extraction of routing/billing scalars ({@code model}, {@code stream}, {@code max_tokens},
 *       {@code stream_options.include_usage}) and exact byte spans for zero-copy passthrough patching.</li>
 *   <li>Base64-image-aware input token estimation directly on raw UTF-8 bytes using SWAR 8-byte
 *       scanning and {@link app.pagefault.hyperllm.protocol.tokenizer.ImageDimensionProbe}.</li>
 * </ol>
 */
public final class StrictJsonRequestScanner {

    private static final VarHandle LONG_LE_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final int MAX_NESTING_DEPTH = 256;
    private static final int REPLY_PRIMING_TOKENS = 3;
    private static final int TOKENS_PER_MESSAGE_OVERHEAD = 3;
    private static final int TOKENS_PER_TOOL_OVERHEAD = 8;
    private static final int BASE64_MIN_IMAGE_BYTES = 64;

    // Container types for iterative nested value scanner
    private static final byte CONTAINER_OBJ = 1;
    private static final byte CONTAINER_ARR = 2;

    // Expect states inside iterative container stack
    private static final byte EXPECT_FIRST = 1;
    private static final byte EXPECT_AFTER_VALUE = 2;

    // Per-depth flags inside iterative container stack
    private static final byte FLAG_OBJ_IMAGE_MIME = 1;

    // Top-level key IDs & duplicate detection bitmask
    private static final int KEY_OTHER = 0;
    private static final int KEY_MODEL = 1;
    private static final int KEY_STREAM = 2;
    private static final int KEY_MAX_TOKENS = 3;
    private static final int KEY_MAX_COMPLETION_TOKENS = 4;
    private static final int KEY_MAX_OUTPUT_TOKENS = 5;
    private static final int KEY_STREAM_OPTIONS = 6;
    private static final int KEY_MESSAGES = 7;
    private static final int KEY_INPUT = 8;
    private static final int KEY_CONTENTS = 9;
    private static final int KEY_SYSTEM = 10;
    private static final int KEY_INSTRUCTIONS = 11;
    private static final int KEY_SYSTEM_INSTRUCTION = 12;
    private static final int KEY_TOOLS = 13;
    private static final int KEY_GENERATION_CONFIG = 14;

    private StrictJsonRequestScanner() {
    }

    public static FastRequestProbe scan(
            ProtocolKind protocol,
            byte[] rawBody,
            InboundRequestContext context
    ) {
        if (rawBody == null || rawBody.length == 0) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Request body must not be empty");
        }
        return new Scanner(protocol, rawBody, context != null ? context : InboundRequestContext.empty()).scanRoot();
    }

    private static final class Scanner {

        private final ProtocolKind protocol;
        private final byte[] raw;
        private final int len;
        private final InboundRequestContext context;

        private int pos;

        // Extracted routing & billing fields
        private String bodyModel;
        private int modelValStart = -1;
        private int modelValEnd = -1;
        private boolean stream;
        private Integer maxTokens;
        private Integer maxCompletionTokens;
        private Integer maxOutputTokens;
        private Integer geminiMaxOutputTokens;
        private boolean hasNonNullMaxTokens;
        private int maxTokensValStart = -1;
        private int maxTokensValEnd = -1;
        private boolean maxTokensInvalidForAnthropic;

        // OpenAI Chat stream_options spans
        private int streamOptionsStart = -1;
        private int streamOptionsEnd = -1;
        private boolean streamOptionsIsObject;
        private boolean streamOptionsHasFields;
        private int includeUsageValStart = -1;
        private int includeUsageValEnd = -1;
        private boolean clientRequestedUsage;

        // Presence / structural validation flags
        private boolean messagesPresent;
        private boolean messagesIsArray;
        private int messagesCount;
        private boolean inputPresent;
        private boolean inputIsNull;
        private boolean contentsPresent;
        private boolean contentsIsArray;
        private int contentsCount;
        private int toolsCount;

        // Root & slow-path flags
        private int rootCloseBrace = -1;
        private boolean rootHasFields;
        private boolean requiresSlowPath;
        private int seenCriticalKeysMask;

        // Prompt token accumulation
        private long promptUtf8Bytes;
        private long promptCodePoints;
        private long imageTokens;

        // Scratch state populated by scanString / scanNumber
        private boolean lastStrHadEscape;
        private int lastStrStart;
        private int lastStrEnd;
        private boolean lastNumIsIntegral;
        private boolean lastNumFitsInt;
        private int lastNumIntVal;

        // Reusable iterative container stack (zero recursion)
        private final byte[] stackType = new byte[MAX_NESTING_DEPTH];
        private final byte[] stackExpect = new byte[MAX_NESTING_DEPTH];
        private final byte[] stackFlags = new byte[MAX_NESTING_DEPTH];

        private Scanner(ProtocolKind protocol, byte[] raw, InboundRequestContext context) {
            this.protocol = protocol;
            this.raw = raw;
            this.len = raw.length;
            this.context = context;
        }

        private FastRequestProbe scanRoot() {
            skipWhitespace();
            if (pos >= len) {
                throw invalid("Request body must not be empty");
            }
            if (raw[pos] != '{') {
                throw invalid("Request body must be a JSON object");
            }
            pos++; // consume '{'
            skipWhitespace();

            if (pos < len && raw[pos] == '}') {
                rootCloseBrace = pos;
                pos++;
            } else {
                while (true) {
                    if (pos >= len || raw[pos] != '"') {
                        throw invalid("Expected JSON object field name");
                    }
                    rootHasFields = true;
                    scanString(false, false);
                    int keyId = resolveTopKey(lastStrStart + 1, lastStrEnd - 1, lastStrHadEscape);
                    if (keyId != KEY_OTHER) {
                        int bit = 1 << keyId;
                        if ((seenCriticalKeysMask & bit) != 0) {
                            throw invalid("Duplicate top-level field in request body");
                        }
                        seenCriticalKeysMask |= bit;
                    }

                    skipWhitespace();
                    if (pos >= len || raw[pos] != ':') {
                        throw invalid("Expected ':' after field name");
                    }
                    pos++; // consume ':'
                    skipWhitespace();
                    if (pos >= len) {
                        throw invalid("Unexpected end of JSON input");
                    }

                    readTopLevelValue(keyId);

                    skipWhitespace();
                    if (pos >= len) {
                        throw invalid("Unterminated JSON root object");
                    }
                    byte next = raw[pos];
                    if (next == ',') {
                        pos++;
                        skipWhitespace();
                        if (pos >= len || raw[pos] == '}') {
                            throw invalid("Trailing comma in JSON object");
                        }
                    } else if (next == '}') {
                        rootCloseBrace = pos;
                        pos++;
                        break;
                    } else {
                        throw invalid("Expected ',' or '}' in JSON object");
                    }
                }
            }

            skipWhitespace();
            if (pos != len) {
                throw invalid("Unexpected trailing content after JSON root");
            }

            return buildValidatedProbe();
        }

        private void readTopLevelValue(int keyId) {
            switch (keyId) {
                case KEY_MODEL -> {
                    int start = pos;
                    if (raw[pos] == '"') {
                        scanString(false, false);
                        modelValStart = start;
                        modelValEnd = lastStrEnd;
                        bodyModel = decodeJsonString(lastStrStart + 1, lastStrEnd - 1, lastStrHadEscape);
                    } else {
                        scanAnyValue(false, false, false);
                    }
                }
                case KEY_STREAM -> {
                    byte b = raw[pos];
                    if (b == 't') {
                        consumeTrue();
                        stream = true;
                    } else if (b == 'f') {
                        consumeFalse();
                        stream = false;
                    } else {
                        scanAnyValue(false, false, false);
                    }
                }
                case KEY_MAX_TOKENS -> {
                    maxTokensValStart = pos;
                    byte b = raw[pos];
                    if (b == 'n') {
                        consumeNull();
                        maxTokensValEnd = pos;
                        hasNonNullMaxTokens = false;
                    } else if (b == '-' || (b >= '0' && b <= '9')) {
                        scanNumber();
                        maxTokensValEnd = pos;
                        hasNonNullMaxTokens = true;
                        if (lastNumIsIntegral && lastNumFitsInt) {
                            maxTokens = lastNumIntVal;
                            if (lastNumIntVal <= 0) {
                                maxTokensInvalidForAnthropic = true;
                            }
                        } else {
                            maxTokensInvalidForAnthropic = true;
                        }
                    } else {
                        scanAnyValue(false, false, false);
                        maxTokensValEnd = pos;
                        hasNonNullMaxTokens = true;
                        maxTokensInvalidForAnthropic = true;
                    }
                }
                case KEY_MAX_COMPLETION_TOKENS -> {
                    byte b = raw[pos];
                    if (b == '-' || (b >= '0' && b <= '9')) {
                        scanNumber();
                        if (lastNumIsIntegral && lastNumFitsInt) {
                            maxCompletionTokens = lastNumIntVal;
                        }
                    } else {
                        scanAnyValue(false, false, false);
                    }
                }
                case KEY_MAX_OUTPUT_TOKENS -> {
                    byte b = raw[pos];
                    if (b == '-' || (b >= '0' && b <= '9')) {
                        scanNumber();
                        if (lastNumIsIntegral && lastNumFitsInt) {
                            maxOutputTokens = lastNumIntVal;
                        }
                    } else {
                        scanAnyValue(false, false, false);
                    }
                }
                case KEY_STREAM_OPTIONS -> {
                    if (protocol == ProtocolKind.OPENAI_CHAT) {
                        scanStreamOptions();
                    } else {
                        scanAnyValue(false, false, false);
                    }
                }
                case KEY_GENERATION_CONFIG -> {
                    if (protocol == ProtocolKind.GEMINI_GENERATE_CONTENT && raw[pos] == '{') {
                        scanGeminiGenerationConfig();
                    } else {
                        scanAnyValue(false, false, false);
                    }
                }
                case KEY_MESSAGES -> {
                    messagesPresent = true;
                    if (raw[pos] == '[') {
                        messagesIsArray = true;
                        boolean inspectRoles = (protocol == ProtocolKind.ANTHROPIC_MESSAGES
                                || protocol == ProtocolKind.OPENAI_CHAT);
                        messagesCount = scanAnyValue(true, false, inspectRoles);
                    } else {
                        scanAnyValue(false, false, false);
                    }
                }
                case KEY_CONTENTS -> {
                    contentsPresent = true;
                    if (raw[pos] == '[') {
                        contentsIsArray = true;
                        contentsCount = scanAnyValue(true, false, false);
                    } else {
                        scanAnyValue(false, false, false);
                    }
                }
                case KEY_INPUT -> {
                    inputPresent = true;
                    if (raw[pos] == 'n') {
                        consumeNull();
                        inputIsNull = true;
                    } else {
                        int count = scanAnyValue(true, false, false);
                        if (count > 0) {
                            messagesCount += count;
                        }
                    }
                }
                case KEY_SYSTEM, KEY_INSTRUCTIONS, KEY_SYSTEM_INSTRUCTION -> {
                    scanAnyValue(true, false, false);
                    messagesCount += 1;
                }
                case KEY_TOOLS -> {
                    toolsCount = scanAnyValue(true, true, false);
                }
                default -> scanAnyValue(false, false, false);
            }
        }

        private void scanStreamOptions() {
            streamOptionsStart = pos;
            if (raw[pos] != '{') {
                streamOptionsIsObject = false;
                scanAnyValue(false, false, false);
                streamOptionsEnd = pos;
                return;
            }
            streamOptionsIsObject = true;
            pos++; // consume '{'
            skipWhitespace();
            boolean seenIncludeUsage = false;
            if (pos < len && raw[pos] == '}') {
                pos++;
                streamOptionsEnd = pos;
                return;
            }
            while (true) {
                if (pos >= len || raw[pos] != '"') {
                    throw invalid("Expected field name in stream_options");
                }
                streamOptionsHasFields = true;
                scanString(false, false);
                boolean isIncludeUsage = keyEqualsAscii(lastStrStart + 1, lastStrEnd - 1, lastStrHadEscape, "include_usage");
                if (isIncludeUsage) {
                    if (seenIncludeUsage) {
                        throw invalid("Duplicate 'include_usage' field in stream_options");
                    }
                    seenIncludeUsage = true;
                }
                skipWhitespace();
                if (pos >= len || raw[pos] != ':') {
                    throw invalid("Expected ':' in stream_options");
                }
                pos++;
                skipWhitespace();
                if (pos >= len) {
                    throw invalid("Unexpected end of stream_options");
                }
                if (isIncludeUsage) {
                    includeUsageValStart = pos;
                    byte b = raw[pos];
                    if (b == 't') {
                        consumeTrue();
                        clientRequestedUsage = true;
                    } else if (b == 'f') {
                        consumeFalse();
                        clientRequestedUsage = false;
                    } else {
                        scanAnyValue(false, false, false);
                        clientRequestedUsage = false;
                    }
                    includeUsageValEnd = pos;
                } else {
                    scanAnyValue(false, false, false);
                }
                skipWhitespace();
                if (pos >= len) {
                    throw invalid("Unterminated stream_options object");
                }
                byte next = raw[pos];
                if (next == ',') {
                    pos++;
                    skipWhitespace();
                    if (pos >= len || raw[pos] == '}') {
                        throw invalid("Trailing comma in stream_options");
                    }
                } else if (next == '}') {
                    pos++;
                    streamOptionsEnd = pos;
                    return;
                } else {
                    throw invalid("Expected ',' or '}' in stream_options");
                }
            }
        }

        private void scanGeminiGenerationConfig() {
            pos++; // consume '{'
            skipWhitespace();
            if (pos < len && raw[pos] == '}') {
                pos++;
                return;
            }
            while (true) {
                if (pos >= len || raw[pos] != '"') {
                    throw invalid("Expected field name in generationConfig");
                }
                scanString(false, false);
                boolean isMaxOut = keyEqualsAscii(lastStrStart + 1, lastStrEnd - 1, lastStrHadEscape, "maxOutputTokens");
                skipWhitespace();
                if (pos >= len || raw[pos] != ':') {
                    throw invalid("Expected ':' in generationConfig");
                }
                pos++;
                skipWhitespace();
                if (pos >= len) {
                    throw invalid("Unexpected end of generationConfig");
                }
                if (isMaxOut && (raw[pos] == '-' || (raw[pos] >= '0' && raw[pos] <= '9'))) {
                    scanNumber();
                    if (lastNumIsIntegral && lastNumFitsInt) {
                        geminiMaxOutputTokens = lastNumIntVal;
                    }
                } else {
                    scanAnyValue(false, false, false);
                }
                skipWhitespace();
                if (pos >= len) {
                    throw invalid("Unterminated generationConfig object");
                }
                byte next = raw[pos];
                if (next == ',') {
                    pos++;
                    skipWhitespace();
                    if (pos >= len || raw[pos] == '}') {
                        throw invalid("Trailing comma in generationConfig");
                    }
                } else if (next == '}') {
                    pos++;
                    return;
                } else {
                    throw invalid("Expected ',' or '}' in generationConfig");
                }
            }
        }

        /**
         * Strictly validates any JSON value iteratively (zero Java call recursion).
         * Returns the top-level array element count if the value was a JSON array, otherwise 0.
         */
        private int scanAnyValue(boolean countPrompt, boolean countKeysAsText, boolean inspectMessageRoles) {
            byte b = raw[pos];
            if (b != '{' && b != '[') {
                scanPrimitive(countPrompt, false, false, false);
                return 0;
            }

            int depth = 0;
            int topArrayCount = 0;
            stackType[0] = (b == '{') ? CONTAINER_OBJ : CONTAINER_ARR;
            stackExpect[0] = EXPECT_FIRST;
            stackFlags[0] = 0;
            pos++;

            while (depth >= 0) {
                skipWhitespace();
                if (pos >= len) {
                    throw invalid("Unexpected end of JSON input");
                }
                byte curType = stackType[depth];
                byte curExpect = stackExpect[depth];

                if (curType == CONTAINER_ARR) {
                    if (curExpect == EXPECT_FIRST) {
                        if (raw[pos] == ']') {
                            pos++;
                            depth--;
                            continue;
                        }
                        if (depth == 0) {
                            topArrayCount++;
                        }
                        stackExpect[depth] = EXPECT_AFTER_VALUE;
                        depth = enterOrScanValue(depth, countPrompt, false, false, false);
                    } else {
                        byte sep = raw[pos];
                        if (sep == ']') {
                            pos++;
                            depth--;
                            continue;
                        }
                        if (sep != ',') {
                            throw invalid("Expected ',' or ']' in JSON array");
                        }
                        pos++;
                        skipWhitespace();
                        if (pos >= len || raw[pos] == ']') {
                            throw invalid("Trailing comma in JSON array");
                        }
                        if (depth == 0) {
                            topArrayCount++;
                        }
                        depth = enterOrScanValue(depth, countPrompt, false, false, false);
                    }
                } else {
                    // CONTAINER_OBJ
                    if (curExpect == EXPECT_FIRST) {
                        if (raw[pos] == '}') {
                            pos++;
                            depth--;
                            continue;
                        }
                        depth = readObjectEntry(depth, countPrompt, countKeysAsText, inspectMessageRoles);
                    } else {
                        byte sep = raw[pos];
                        if (sep == '}') {
                            pos++;
                            depth--;
                            continue;
                        }
                        if (sep != ',') {
                            throw invalid("Expected ',' or '}' in JSON object");
                        }
                        pos++;
                        skipWhitespace();
                        if (pos >= len || raw[pos] != '"') {
                            throw invalid("Expected JSON object field name after ','");
                        }
                        depth = readObjectEntry(depth, countPrompt, countKeysAsText, inspectMessageRoles);
                    }
                }
            }
            return topArrayCount;
        }

        private int readObjectEntry(int depth, boolean countPrompt, boolean countKeysAsText, boolean inspectMessageRoles) {
            if (raw[pos] != '"') {
                throw invalid("Expected JSON object field name");
            }
            scanString(countKeysAsText, false);
            int keyStart = lastStrStart + 1;
            int keyEnd = lastStrEnd - 1;
            int keyLen = keyEnd - keyStart;

            boolean isDataKey = false;
            boolean isMimeKey = false;
            boolean isRoleKey = false;
            if (!lastStrHadEscape) {
                if (countPrompt) {
                    if (keyLen == 4 && matchesAscii(keyStart, "data")) {
                        isDataKey = true;
                    } else if ((keyLen == 10 && matchesAscii(keyStart, "media_type"))
                            || (keyLen == 8 && matchesAscii(keyStart, "mimeType"))) {
                        isMimeKey = true;
                    }
                }
                if (inspectMessageRoles && depth == 1 && keyLen == 4 && matchesAscii(keyStart, "role")) {
                    isRoleKey = true;
                }
            } else if (inspectMessageRoles && depth == 1 && keyEqualsAscii(keyStart, keyEnd, true, "role")) {
                isRoleKey = true;
            }

            skipWhitespace();
            if (pos >= len || raw[pos] != ':') {
                throw invalid("Expected ':' after JSON object key");
            }
            pos++;
            skipWhitespace();
            if (pos >= len) {
                throw invalid("Unexpected end of JSON input after ':'");
            }

            int valStart = pos;
            stackExpect[depth] = EXPECT_AFTER_VALUE;
            int newDepth = enterOrScanValue(depth, countPrompt, isDataKey, isMimeKey, isRoleKey);
            if (newDepth == depth && isMimeKey && raw[valStart] == '"'
                    && lastStrStart == valStart && lastStrEnd > lastStrStart + 7) {
                if (startsWithAsciiIgnoreCase(lastStrStart + 1, lastStrEnd - 1, "image/")) {
                    stackFlags[depth] |= FLAG_OBJ_IMAGE_MIME;
                }
            }
            return newDepth;
        }

        private int enterOrScanValue(
                int depth,
                boolean countPrompt,
                boolean isDataKey,
                boolean isMimeKey,
                boolean isRoleKey
        ) {
            byte b = raw[pos];
            if (b == '{' || b == '[') {
                int nextDepth = depth + 1;
                if (nextDepth >= MAX_NESTING_DEPTH) {
                    throw invalid("JSON nesting depth exceeds maximum allowed (" + MAX_NESTING_DEPTH + ")");
                }
                stackType[nextDepth] = (b == '{') ? CONTAINER_OBJ : CONTAINER_ARR;
                stackExpect[nextDepth] = EXPECT_FIRST;
                stackFlags[nextDepth] = 0;
                pos++;
                return nextDepth;
            }
            boolean objHasImageMime = (stackFlags[depth] & FLAG_OBJ_IMAGE_MIME) != 0;
            scanPrimitive(countPrompt, isDataKey, objHasImageMime, isRoleKey);
            return depth;
        }

        private void scanPrimitive(
                boolean countPrompt,
                boolean isDataKey,
                boolean objHasImageMime,
                boolean isRoleKey
        ) {
            byte b = raw[pos];
            switch (b) {
                case '"' -> {
                    boolean maybeBase64Image = countPrompt && isLikelyBase64ImageValue(pos + 1, isDataKey, objHasImageMime);
                    if (maybeBase64Image) {
                        scanString(false, false);
                        accountBase64Image(lastStrStart + 1, lastStrEnd - 1, isDataKey, objHasImageMime);
                    } else {
                        scanString(countPrompt, false);
                    }
                    if (isRoleKey) {
                        inspectMessageRole(lastStrStart + 1, lastStrEnd - 1, lastStrHadEscape);
                    }
                }
                case 't' -> consumeTrue();
                case 'f' -> consumeFalse();
                case 'n' -> consumeNull();
                default -> {
                    if (b == '-' || (b >= '0' && b <= '9')) {
                        scanNumber();
                    } else {
                        throw invalid("Unexpected byte '" + (char) (b & 0xFF) + "' in JSON value");
                    }
                }
            }
        }

        private boolean isLikelyBase64ImageValue(int contentStart, boolean isDataKey, boolean objHasImageMime) {
            int rem = len - contentStart;
            if (rem >= 12 && startsWithAsciiIgnoreCase(contentStart, len, "data:image/")) {
                return true;
            }
            if (isDataKey && rem >= 16) {
                if (objHasImageMime) {
                    return true;
                }
                if (rem >= BASE64_MIN_IMAGE_BYTES) {
                    // Check common base64 image magic headers (PNG, JPEG, JSON-escaped JPEG, GIF, WebP)
                    return matchesAscii(contentStart, "iVBOR")
                            || matchesAscii(contentStart, "/9j/")
                            || matchesAscii(contentStart, "\\/9j")
                            || matchesAscii(contentStart, "R0lGOD")
                            || matchesAscii(contentStart, "UklGR");
                }
            }
            return false;
        }

        private void accountBase64Image(int contentStart, int contentEnd, boolean isDataKey, boolean objHasImageMime) {
            int contentLen = contentEnd - contentStart;
            if (contentLen >= 12 && startsWithAsciiIgnoreCase(contentStart, contentEnd, "data:image/")) {
                int comma = -1;
                int searchLimit = Math.min(contentEnd, contentStart + 128);
                for (int i = contentStart + 11; i < searchLimit; i++) {
                    if (raw[i] == ',') {
                        comma = i;
                        break;
                    }
                }
                if (comma > 0 && contentEnd - (comma + 1) >= 16) {
                    imageTokens += TokenEstimator.estimateImageTokensFromBase64Utf8(
                            raw, comma + 1, contentEnd - (comma + 1));
                    return;
                }
            }
            if (isDataKey && (contentLen >= BASE64_MIN_IMAGE_BYTES || (objHasImageMime && contentLen >= 16))) {
                imageTokens += TokenEstimator.estimateImageTokensFromBase64Utf8(raw, contentStart, contentLen);
                return;
            }
            // Fallback: wasn't actually a base64 image payload; count as normal text
            promptUtf8Bytes += contentLen;
            promptCodePoints += contentLen;
        }

        private void inspectMessageRole(int contentStart, int contentEnd, boolean hasEscape) {
            String role = hasEscape
                    ? decodeJsonString(contentStart, contentEnd, true).toLowerCase(java.util.Locale.ROOT)
                    : null;
            if (protocol == ProtocolKind.ANTHROPIC_MESSAGES) {
                if (role == null) {
                    if (equalsAsciiIgnoreCase(contentStart, contentEnd, "user")
                            || equalsAsciiIgnoreCase(contentStart, contentEnd, "assistant")) {
                        return;
                    }
                    if (equalsAsciiIgnoreCase(contentStart, contentEnd, "system")
                            || equalsAsciiIgnoreCase(contentStart, contentEnd, "developer")) {
                        requiresSlowPath = true;
                        return;
                    }
                    throw invalid("Unsupported Anthropic role: "
                            + new String(raw, contentStart, contentEnd - contentStart, StandardCharsets.UTF_8));
                }
                switch (role) {
                    case "user", "assistant" -> {}
                    case "system", "developer" -> requiresSlowPath = true;
                    default -> throw invalid("Unsupported Anthropic role: " + role);
                }
            } else if (protocol == ProtocolKind.OPENAI_CHAT) {
                if (role == null) {
                    if (equalsAsciiIgnoreCase(contentStart, contentEnd, "user")
                            || equalsAsciiIgnoreCase(contentStart, contentEnd, "assistant")
                            || equalsAsciiIgnoreCase(contentStart, contentEnd, "system")
                            || equalsAsciiIgnoreCase(contentStart, contentEnd, "developer")
                            || equalsAsciiIgnoreCase(contentStart, contentEnd, "tool")
                            || equalsAsciiIgnoreCase(contentStart, contentEnd, "function")) {
                        return;
                    }
                    throw invalid("Unsupported message role: "
                            + new String(raw, contentStart, contentEnd - contentStart, StandardCharsets.UTF_8));
                }
                switch (role) {
                    case "user", "assistant", "system", "developer", "tool", "function" -> {}
                    default -> throw invalid("Unsupported message role: " + role);
                }
            }
        }

        /**
         * Strictly scans a JSON string starting at {@code raw[pos] == '"'}.
         * Uses 8-byte SWAR skipping for pure printable ASCII segments and strict RFC 3629 UTF-8 +
         * RFC 8259 escape validation for everything else.
         */
        private void scanString(boolean countText, boolean unused) {
            int start = pos;
            pos++; // consume opening '"'
            boolean hadEscape = false;
            long localUtf8 = 0L;
            long localCp = 0L;

            while (pos < len) {
                // SWAR fast loop: 8 bytes at a time for pure printable ASCII with no '"' or '\'
                while (pos + 8 <= len) {
                    long word = (long) LONG_LE_VIEW.get(raw, pos);
                    if ((word & 0x8080808080808080L) != 0L) {
                        break;
                    }
                    long lowCtrl = (word - 0x2020202020202020L) & ~word & 0x8080808080808080L;
                    long q = word ^ 0x2222222222222222L;
                    long hasQuote = (q - 0x0101010101010101L) & ~q & 0x8080808080808080L;
                    long bs = word ^ 0x5C5C5C5C5C5C5C5CL;
                    long hasBs = (bs - 0x0101010101010101L) & ~bs & 0x8080808080808080L;
                    if ((lowCtrl | hasQuote | hasBs) != 0L) {
                        break;
                    }
                    pos += 8;
                    if (countText) {
                        localUtf8 += 8L;
                        localCp += 8L;
                    }
                }
                if (pos >= len) {
                    break;
                }

                int b0 = raw[pos] & 0xFF;
                if (b0 == '"') {
                    pos++;
                    lastStrStart = start;
                    lastStrEnd = pos;
                    lastStrHadEscape = hadEscape;
                    if (countText) {
                        promptUtf8Bytes += localUtf8;
                        promptCodePoints += localCp;
                    }
                    return;
                }
                if (b0 == '\\') {
                    hadEscape = true;
                    pos++;
                    if (pos >= len) {
                        throw invalid("Unterminated escape sequence in JSON string");
                    }
                    byte esc = raw[pos++];
                    switch (esc) {
                        case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
                            if (countText) {
                                localUtf8 += 1L;
                                localCp += 1L;
                            }
                        }
                        case 'u' -> {
                            int cu = parseHex4();
                            if (countText) {
                                if (cu >= 0xD800 && cu <= 0xDBFF
                                        && pos + 6 <= len && raw[pos] == '\\' && raw[pos + 1] == 'u') {
                                    int saved = pos;
                                    pos += 2;
                                    int low = parseHex4();
                                    if (low >= 0xDC00 && low <= 0xDFFF) {
                                        localUtf8 += 4L;
                                        localCp += 1L;
                                    } else {
                                        // Unpaired high surrogate followed by another unicode escape: rewind second escape
                                        pos = saved;
                                        localUtf8 += 1L;
                                        localCp += 1L;
                                    }
                                } else if (cu < 0x80) {
                                    localUtf8 += 1L;
                                    localCp += 1L;
                                } else if (cu < 0x800) {
                                    localUtf8 += 2L;
                                    localCp += 1L;
                                } else if (cu >= 0xD800 && cu <= 0xDFFF) {
                                    localUtf8 += 1L;
                                    localCp += 1L;
                                } else {
                                    localUtf8 += 3L;
                                    localCp += 1L;
                                }
                            }
                        }
                        default -> throw invalid("Invalid JSON escape sequence '\\" + (char) (esc & 0xFF) + "'");
                    }
                    continue;
                }
                if (b0 < 0x20) {
                    throw invalid("Unescaped control character 0x" + Integer.toHexString(b0) + " in JSON string");
                }
                if (b0 < 0x80) {
                    pos++;
                    if (countText) {
                        localUtf8 += 1L;
                        localCp += 1L;
                    }
                    continue;
                }
                // Strict RFC 3629 UTF-8 multi-byte validation
                int width = validateUtf8Sequence(b0);
                pos += width;
                if (countText) {
                    localUtf8 += width;
                    localCp += 1L;
                }
            }
            throw invalid("Unterminated JSON string");
        }

        private int validateUtf8Sequence(int b0) {
            if (b0 >= 0xC2 && b0 <= 0xDF) {
                if (pos + 1 >= len || !isUtf8Cont(raw[pos + 1])) {
                    throw invalid("Invalid 2-byte UTF-8 sequence in JSON string");
                }
                return 2;
            }
            if (b0 >= 0xE0 && b0 <= 0xEF) {
                if (pos + 2 >= len) {
                    throw invalid("Truncated 3-byte UTF-8 sequence in JSON string");
                }
                int b1 = raw[pos + 1] & 0xFF;
                int b2 = raw[pos + 2] & 0xFF;
                if ((b2 & 0xC0) != 0x80) {
                    throw invalid("Invalid 3-byte UTF-8 sequence in JSON string");
                }
                if (b0 == 0xE0) {
                    if (b1 < 0xA0 || b1 > 0xBF) {
                        throw invalid("Overlong 3-byte UTF-8 sequence in JSON string");
                    }
                } else if (b0 == 0xED) {
                    if (b1 < 0x80 || b1 > 0x9F) {
                        throw invalid("UTF-16 surrogate encoded in UTF-8 JSON string");
                    }
                } else if ((b1 & 0xC0) != 0x80) {
                    throw invalid("Invalid 3-byte UTF-8 sequence in JSON string");
                }
                return 3;
            }
            if (b0 >= 0xF0 && b0 <= 0xF4) {
                if (pos + 3 >= len) {
                    throw invalid("Truncated 4-byte UTF-8 sequence in JSON string");
                }
                int b1 = raw[pos + 1] & 0xFF;
                int b2 = raw[pos + 2] & 0xFF;
                int b3 = raw[pos + 3] & 0xFF;
                if ((b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80) {
                    throw invalid("Invalid 4-byte UTF-8 sequence in JSON string");
                }
                if (b0 == 0xF0) {
                    if (b1 < 0x90 || b1 > 0xBF) {
                        throw invalid("Overlong 4-byte UTF-8 sequence in JSON string");
                    }
                } else if (b0 == 0xF4) {
                    if (b1 < 0x80 || b1 > 0x8F) {
                        throw invalid("Out-of-range 4-byte UTF-8 sequence in JSON string");
                    }
                } else if ((b1 & 0xC0) != 0x80) {
                    throw invalid("Invalid 4-byte UTF-8 sequence in JSON string");
                }
                return 4;
            }
            throw invalid("Invalid UTF-8 lead byte 0x" + Integer.toHexString(b0) + " in JSON string");
        }

        private static boolean isUtf8Cont(byte b) {
            return (b & 0xC0) == 0x80;
        }

        private int parseHex4() {
            if (pos + 4 > len) {
                throw invalid("Truncated \\uXXXX escape in JSON string");
            }
            int d0 = hexDigit(raw[pos]);
            int d1 = hexDigit(raw[pos + 1]);
            int d2 = hexDigit(raw[pos + 2]);
            int d3 = hexDigit(raw[pos + 3]);
            if ((d0 | d1 | d2 | d3) < 0) {
                throw invalid("Invalid hex digit in \\uXXXX escape");
            }
            pos += 4;
            return (d0 << 12) | (d1 << 8) | (d2 << 4) | d3;
        }

        private static int hexDigit(byte b) {
            if (b >= '0' && b <= '9') {
                return b - '0';
            }
            if (b >= 'a' && b <= 'f') {
                return b - 'a' + 10;
            }
            if (b >= 'A' && b <= 'F') {
                return b - 'A' + 10;
            }
            return -1;
        }

        private void scanNumber() {
            boolean negative = false;
            if (raw[pos] == '-') {
                negative = true;
                pos++;
                if (pos >= len) {
                    throw invalid("Incomplete negative number in JSON");
                }
            }
            byte first = raw[pos];
            long acc = 0L;
            boolean overflow = false;
            if (first == '0') {
                pos++;
                if (pos < len && raw[pos] >= '0' && raw[pos] <= '9') {
                    throw invalid("Leading zero in JSON number");
                }
            } else if (first >= '1' && first <= '9') {
                acc = first - '0';
                pos++;
                while (pos < len && raw[pos] >= '0' && raw[pos] <= '9') {
                    int d = raw[pos] - '0';
                    if (acc <= 214748364L) {
                        acc = acc * 10L + d;
                    } else {
                        overflow = true;
                    }
                    pos++;
                }
            } else {
                throw invalid("Invalid JSON number");
            }

            boolean integral = true;
            if (pos < len && raw[pos] == '.') {
                integral = false;
                pos++;
                if (pos >= len || raw[pos] < '0' || raw[pos] > '9') {
                    throw invalid("Missing fraction digits in JSON number");
                }
                while (pos < len && raw[pos] >= '0' && raw[pos] <= '9') {
                    pos++;
                }
            }
            if (pos < len && (raw[pos] == 'e' || raw[pos] == 'E')) {
                integral = false;
                pos++;
                if (pos < len && (raw[pos] == '+' || raw[pos] == '-')) {
                    pos++;
                }
                if (pos >= len || raw[pos] < '0' || raw[pos] > '9') {
                    throw invalid("Missing exponent digits in JSON number");
                }
                while (pos < len && raw[pos] >= '0' && raw[pos] <= '9') {
                    pos++;
                }
            }

            lastNumIsIntegral = integral;
            if (integral && !overflow) {
                long signed = negative ? -acc : acc;
                if (signed >= Integer.MIN_VALUE && signed <= Integer.MAX_VALUE) {
                    lastNumFitsInt = true;
                    lastNumIntVal = (int) signed;
                } else {
                    lastNumFitsInt = false;
                }
            } else {
                lastNumFitsInt = false;
            }
        }

        private void consumeTrue() {
            if (pos + 4 <= len && raw[pos] == 't' && raw[pos + 1] == 'r'
                    && raw[pos + 2] == 'u' && raw[pos + 3] == 'e') {
                pos += 4;
                return;
            }
            throw invalid("Invalid JSON literal (expected 'true')");
        }

        private void consumeFalse() {
            if (pos + 5 <= len && raw[pos] == 'f' && raw[pos + 1] == 'a'
                    && raw[pos + 2] == 'l' && raw[pos + 3] == 's' && raw[pos + 4] == 'e') {
                pos += 5;
                return;
            }
            throw invalid("Invalid JSON literal (expected 'false')");
        }

        private void consumeNull() {
            if (pos + 4 <= len && raw[pos] == 'n' && raw[pos + 1] == 'u'
                    && raw[pos + 2] == 'l' && raw[pos + 3] == 'l') {
                pos += 4;
                return;
            }
            throw invalid("Invalid JSON literal (expected 'null')");
        }

        private void skipWhitespace() {
            while (pos < len) {
                byte b = raw[pos];
                if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        private int resolveTopKey(int start, int end, boolean hadEscape) {
            if (hadEscape) {
                String unescaped = decodeJsonString(start, end, true);
                return switch (unescaped) {
                    case "model" -> KEY_MODEL;
                    case "stream" -> KEY_STREAM;
                    case "max_tokens" -> KEY_MAX_TOKENS;
                    case "max_completion_tokens" -> KEY_MAX_COMPLETION_TOKENS;
                    case "max_output_tokens" -> KEY_MAX_OUTPUT_TOKENS;
                    case "stream_options" -> KEY_STREAM_OPTIONS;
                    case "messages" -> KEY_MESSAGES;
                    case "input" -> KEY_INPUT;
                    case "contents" -> KEY_CONTENTS;
                    case "system" -> KEY_SYSTEM;
                    case "instructions" -> KEY_INSTRUCTIONS;
                    case "systemInstruction" -> KEY_SYSTEM_INSTRUCTION;
                    case "tools" -> KEY_TOOLS;
                    case "generationConfig" -> KEY_GENERATION_CONFIG;
                    default -> KEY_OTHER;
                };
            }
            int klen = end - start;
            return switch (klen) {
                case 5 -> matchesAscii(start, "model") ? KEY_MODEL
                        : matchesAscii(start, "input") ? KEY_INPUT
                        : matchesAscii(start, "tools") ? KEY_TOOLS : KEY_OTHER;
                case 6 -> matchesAscii(start, "stream") ? KEY_STREAM
                        : matchesAscii(start, "system") ? KEY_SYSTEM : KEY_OTHER;
                case 8 -> matchesAscii(start, "messages") ? KEY_MESSAGES
                        : matchesAscii(start, "contents") ? KEY_CONTENTS : KEY_OTHER;
                case 10 -> matchesAscii(start, "max_tokens") ? KEY_MAX_TOKENS : KEY_OTHER;
                case 12 -> matchesAscii(start, "instructions") ? KEY_INSTRUCTIONS : KEY_OTHER;
                case 14 -> matchesAscii(start, "stream_options") ? KEY_STREAM_OPTIONS : KEY_OTHER;
                case 16 -> matchesAscii(start, "generationConfig") ? KEY_GENERATION_CONFIG : KEY_OTHER;
                case 17 -> matchesAscii(start, "max_output_tokens") ? KEY_MAX_OUTPUT_TOKENS
                        : matchesAscii(start, "systemInstruction") ? KEY_SYSTEM_INSTRUCTION : KEY_OTHER;
                case 21 -> matchesAscii(start, "max_completion_tokens") ? KEY_MAX_COMPLETION_TOKENS : KEY_OTHER;
                default -> KEY_OTHER;
            };
        }

        private boolean keyEqualsAscii(int start, int end, boolean hadEscape, String expected) {
            if (!hadEscape) {
                return (end - start) == expected.length() && matchesAscii(start, expected);
            }
            return expected.equals(decodeJsonString(start, end, true));
        }

        private boolean matchesAscii(int offset, String ascii) {
            int n = ascii.length();
            if (offset + n > len) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                if (raw[offset + i] != (byte) ascii.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private boolean startsWithAsciiIgnoreCase(int start, int end, String asciiLower) {
            int n = asciiLower.length();
            if (end - start < n) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                byte b = raw[start + i];
                if (b >= 'A' && b <= 'Z') {
                    b = (byte) (b + 32);
                }
                if (b != (byte) asciiLower.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private boolean equalsAsciiIgnoreCase(int start, int end, String asciiLower) {
            int n = asciiLower.length();
            if (end - start != n) {
                return false;
            }
            return startsWithAsciiIgnoreCase(start, end, asciiLower);
        }

        private String decodeJsonString(int start, int end, boolean hadEscape) {
            if (!hadEscape) {
                return new String(raw, start, end - start, StandardCharsets.UTF_8);
            }
            StringBuilder sb = new StringBuilder(end - start);
            int i = start;
            while (i < end) {
                int b0 = raw[i] & 0xFF;
                if (b0 == '\\') {
                    byte esc = raw[++i];
                    i++;
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            int cu = (hexDigit(raw[i]) << 12)
                                    | (hexDigit(raw[i + 1]) << 8)
                                    | (hexDigit(raw[i + 2]) << 4)
                                    | hexDigit(raw[i + 3]);
                            i += 4;
                            sb.append((char) cu);
                        }
                        default -> throw invalid("Invalid JSON escape sequence");
                    }
                } else if (b0 < 0x80) {
                    sb.append((char) b0);
                    i++;
                } else {
                    int segStart = i;
                    while (i < end && raw[i] != '\\') {
                        i++;
                    }
                    sb.append(new String(raw, segStart, i - segStart, StandardCharsets.UTF_8));
                }
            }
            return sb.toString();
        }

        private FastRequestProbe buildValidatedProbe() {
            String resolvedModel;
            boolean resolvedStream;
            Integer resolvedMaxOut;
            boolean resolvedClientUsage;

            switch (protocol) {
                case OPENAI_CHAT -> {
                    if (bodyModel == null || bodyModel.isBlank()) {
                        throw invalid("Field 'model' is required and must be non-blank");
                    }
                    if (!messagesPresent || !messagesIsArray || messagesCount <= 0) {
                        throw invalid("Field 'messages' must be a non-empty array");
                    }
                    resolvedModel = bodyModel.trim();
                    resolvedStream = stream;
                    resolvedMaxOut = maxCompletionTokens != null ? maxCompletionTokens : maxTokens;
                    resolvedClientUsage = clientRequestedUsage;
                }
                case OPENAI_RESPONSES -> {
                    if (bodyModel == null || bodyModel.isBlank()) {
                        throw invalid("Field 'model' is required and must be non-blank");
                    }
                    if (!inputPresent || inputIsNull) {
                        throw invalid("Field 'input' is required");
                    }
                    resolvedModel = bodyModel.trim();
                    resolvedStream = stream;
                    resolvedMaxOut = maxOutputTokens;
                    resolvedClientUsage = true;
                }
                case ANTHROPIC_MESSAGES -> {
                    if (bodyModel == null || bodyModel.isBlank()) {
                        throw invalid("Field 'model' is required and must be non-blank");
                    }
                    if (maxTokensInvalidForAnthropic) {
                        throw invalid("Field 'max_tokens' must be a positive integer");
                    }
                    if (!messagesPresent || !messagesIsArray || messagesCount <= 0) {
                        throw invalid("Field 'messages' must be a non-empty array");
                    }
                    resolvedModel = bodyModel.trim();
                    resolvedStream = stream;
                    resolvedMaxOut = maxTokens;
                    resolvedClientUsage = true;
                }
                case GEMINI_GENERATE_CONTENT -> {
                    resolvedModel = (context.pathModel() != null && !context.pathModel().isBlank())
                            ? context.pathModel().trim()
                            : (bodyModel != null ? bodyModel.trim() : null);
                    if (resolvedModel == null || resolvedModel.isBlank()) {
                        throw invalid("Model must be specified in the Gemini URL path '/v1beta/models/{model}:generateContent'");
                    }
                    if (!contentsPresent || !contentsIsArray || contentsCount <= 0) {
                        throw invalid("Field 'contents' must be a non-empty array");
                    }
                    resolvedStream = Boolean.TRUE.equals(context.pathStream());
                    resolvedMaxOut = geminiMaxOutputTokens;
                    resolvedClientUsage = true;
                }
                default -> throw invalid("Unsupported protocol kind: " + protocol);
            }

            long byBytes = (promptUtf8Bytes + 3L) / 4L;
            long byChars = (promptCodePoints + 2L) / 3L;
            long structural = (long) Math.max(messagesCount, contentsCount) * TOKENS_PER_MESSAGE_OVERHEAD
                    + (long) toolsCount * TOKENS_PER_TOOL_OVERHEAD;
            long totalEstimate = REPLY_PRIMING_TOKENS + structural + Math.max(byBytes, byChars) + imageTokens;
            int estimatedPromptTokens = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, totalEstimate));

            return new FastRequestProbe(
                    protocol,
                    resolvedModel,
                    resolvedStream,
                    resolvedMaxOut,
                    resolvedClientUsage,
                    context.geminiAltSse(),
                    context.anthropicVersion(),
                    context.anthropicBeta(),
                    estimatedPromptTokens,
                    modelValStart,
                    modelValEnd,
                    streamOptionsStart,
                    streamOptionsEnd,
                    streamOptionsIsObject,
                    streamOptionsHasFields,
                    includeUsageValStart,
                    includeUsageValEnd,
                    hasNonNullMaxTokens,
                    maxTokensValStart,
                    maxTokensValEnd,
                    rootCloseBrace,
                    rootHasFields,
                    requiresSlowPath
            );
        }

        private static HyperLlmException invalid(String message) {
            return new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, message);
        }
    }
}
