package app.pagefault.hyperllm.protocol.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Byte-level SSE event splitter that handles multi-byte UTF-8 characters split across TCP chunks
 * and emits intact raw event frames along with decoded {@code event} and {@code data} lines.
 */
public final class SseEventSplitter {

    public record SseEvent(byte[] rawBytes, String eventName, String dataPayload) {
    }

    private byte[] buffer = new byte[0];

    public List<SseEvent> push(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return List.of();
        }
        byte[] combined = Arrays.copyOf(buffer, buffer.length + chunk.length);
        System.arraycopy(chunk, 0, combined, buffer.length, chunk.length);
        buffer = combined;
        return drainCompleteEvents();
    }

    public List<SseEvent> flushRemainder() {
        if (buffer.length == 0) {
            return List.of();
        }
        byte[] trailing = buffer;
        buffer = new byte[0];
        SseEvent parsed = parseFrame(trailing);
        return (parsed.dataPayload().isEmpty() && parsed.eventName().isEmpty())
                ? List.of()
                : List.of(parsed);
    }

    private List<SseEvent> drainCompleteEvents() {
        List<SseEvent> events = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < buffer.length) {
            int delimLen = boundaryLength(buffer, i);
            if (delimLen > 0) {
                int end = i + delimLen;
                byte[] frame = Arrays.copyOfRange(buffer, start, end);
                events.add(parseFrame(frame));
                start = end;
                i = end;
            } else {
                i++;
            }
        }
        if (start > 0) {
            buffer = (start < buffer.length) ? Arrays.copyOfRange(buffer, start, buffer.length) : new byte[0];
        }
        return events;
    }

    private static int boundaryLength(byte[] bytes, int idx) {
        if (idx + 1 < bytes.length && bytes[idx] == '\n' && bytes[idx + 1] == '\n') {
            return 2;
        }
        if (idx + 3 < bytes.length && bytes[idx] == '\r' && bytes[idx + 1] == '\n'
                && bytes[idx + 2] == '\r' && bytes[idx + 3] == '\n') {
            return 4;
        }
        return 0;
    }

    private static SseEvent parseFrame(byte[] rawBytes) {
        String text = new String(rawBytes, StandardCharsets.UTF_8);
        String eventName = "";
        StringBuilder data = new StringBuilder();
        int lineStart = 0;
        while (lineStart < text.length()) {
            int nl = text.indexOf('\n', lineStart);
            String line = (nl >= 0) ? text.substring(lineStart, nl) : text.substring(lineStart);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.startsWith("event:")) {
                eventName = trimLeadingSpace(line.substring("event:".length()));
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(trimLeadingSpace(line.substring("data:".length())));
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        return new SseEvent(rawBytes, eventName, data.toString());
    }

    private static String trimLeadingSpace(String value) {
        return (!value.isEmpty() && value.charAt(0) == ' ') ? value.substring(1) : value;
    }

    public static byte[] buildDataFrame(String payload) {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(payloadBytes.length + 10);
        out.writeBytes("data: ".getBytes(StandardCharsets.UTF_8));
        out.writeBytes(payloadBytes);
        out.write('\n');
        out.write('\n');
        return out.toByteArray();
    }

    public static byte[] buildNamedEventFrame(String eventName, String jsonPayload) {
        byte[] nameBytes = eventName.getBytes(StandardCharsets.UTF_8);
        byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(nameBytes.length + payloadBytes.length + 20);
        out.writeBytes("event: ".getBytes(StandardCharsets.UTF_8));
        out.writeBytes(nameBytes);
        out.write('\n');
        out.writeBytes("data: ".getBytes(StandardCharsets.UTF_8));
        out.writeBytes(payloadBytes);
        out.write('\n');
        out.write('\n');
        return out.toByteArray();
    }
}
