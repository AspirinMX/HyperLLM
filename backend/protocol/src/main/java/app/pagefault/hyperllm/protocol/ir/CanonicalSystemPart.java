package app.pagefault.hyperllm.protocol.ir;

import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.util.Objects;

/**
 * System or developer instruction block hoisted to the top level of {@link CanonicalRequest}.
 */
public final class CanonicalSystemPart {

    private final JsonUtf8Text utf8Text;
    private final boolean developerPriority;
    private final CacheControl cacheControl;

    public CanonicalSystemPart(
            String text,
            boolean developerPriority,
            CacheControl cacheControl
    ) {
        this(JsonUtf8Text.ofString(text), developerPriority, cacheControl);
    }

    private CanonicalSystemPart(
            JsonUtf8Text utf8Text,
            boolean developerPriority,
            CacheControl cacheControl
    ) {
        this.utf8Text = utf8Text != null ? utf8Text : JsonUtf8Text.EMPTY;
        this.developerPriority = developerPriority;
        this.cacheControl = cacheControl;
    }

    public static CanonicalSystemPart ofUtf8(
            JsonUtf8Text utf8Text,
            boolean developerPriority,
            CacheControl cacheControl
    ) {
        return new CanonicalSystemPart(utf8Text, developerPriority, cacheControl);
    }

    public JsonUtf8Text utf8Text() {
        return utf8Text;
    }

    public String text() {
        return utf8Text.asString();
    }

    public boolean developerPriority() {
        return developerPriority;
    }

    public CacheControl cacheControl() {
        return cacheControl;
    }

    public boolean isEmpty() {
        return utf8Text.isEmpty();
    }

    public int estimatedTokens() {
        return utf8Text.estimatedTokens();
    }

    public int estimatedWireBytes() {
        return utf8Text.estimatedWireBytes();
    }

    public void writeJsonValue(JsonGenerator gen) throws IOException {
        utf8Text.writeJsonValue(gen);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof CanonicalSystemPart other) {
            return developerPriority == other.developerPriority
                    && Objects.equals(utf8Text, other.utf8Text)
                    && Objects.equals(cacheControl, other.cacheControl);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(utf8Text, developerPriority, cacheControl);
    }

    @Override
    public String toString() {
        return "CanonicalSystemPart[text=" + text()
                + ", developerPriority=" + developerPriority
                + ", cacheControl=" + cacheControl + "]";
    }
}
