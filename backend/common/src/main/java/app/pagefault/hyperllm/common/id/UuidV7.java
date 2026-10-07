package app.pagefault.hyperllm.common.id;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

/**
 * Application-level generator for time-ordered UUIDv7 identifiers.
 */
public final class UuidV7 {

    private UuidV7() {
    }

    public static UUID next() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
