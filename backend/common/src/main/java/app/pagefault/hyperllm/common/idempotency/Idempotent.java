package app.pagefault.hyperllm.common.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method whose execution must happen at most once per resolved key.
 *
 * <p>This is <strong>request level</strong> idempotency: it stops the same business request from
 * being executed twice (a retried call, a replayed callback, a double click). It is deliberately not
 * the mechanism that makes an <em>event</em> apply once — that guarantee has to be written into the
 * same atomic step as the state change (a Lua script for Redis, the unique key of
 * {@code wallet_ledger} for PostgreSQL), because a marker written outside that step cannot be
 * atomic with it.
 *
 * <p>The key is a SpEL expression evaluated against the method arguments, so {@code #p0}, {@code #a0}
 * and (when the build enables {@code -parameters}) the parameter names are all available. A raw
 * string without {@code #} is used verbatim.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** SpEL expression, or a literal key when it contains no {@code #} or {@code {}.} */
    String key();

    /** Optional namespace prepended to the key, so unrelated call sites cannot collide. */
    String namespace() default "";

    /** How long a claim stays in flight before it is considered abandoned. */
    long inFlightSeconds() default 30;

    /** How long a completed result stays replayable. */
    long resultSeconds() default 86400;

    /** What to do when the key was already completed. */
    DuplicatePolicy onDuplicate() default DuplicatePolicy.REJECT;

    /**
     * Whether a failure of the backing store should let the call through.
     *
     * <p>Defaults to {@code false}: an idempotency guard that cannot record its claim must not
     * pretend the call was protected. Set it to {@code true} only where a duplicate is cheaper than
     * an outage.
     */
    boolean failOpen() default false;
}
