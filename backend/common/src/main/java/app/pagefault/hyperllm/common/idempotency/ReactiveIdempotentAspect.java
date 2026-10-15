package app.pagefault.hyperllm.common.idempotency;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * Applies {@link Idempotent} to reactive methods.
 *
 * <p>The claim is taken at <strong>subscription</strong> time, never at assembly time: a handler that
 * builds a pipeline and never subscribes must not burn the key. Only
 * {@link DuplicatePolicy#REJECT} is supported here, because replaying a rendered stream body is not
 * a meaningful operation; a completed key therefore rejects just like an in-flight one.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ReactiveIdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(ReactiveIdempotentAspect.class);

    private final IdempotencyStore store;

    public ReactiveIdempotentAspect(IdempotencyStore store) {
        this.store = store;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Class<?> returnType = method.getReturnType();
        boolean mono = Mono.class.isAssignableFrom(returnType);
        boolean flux = Flux.class.isAssignableFrom(returnType);
        if (!mono && !flux) {
            return joinPoint.proceed();
        }
        if (idempotent.onDuplicate() == DuplicatePolicy.REPLAY) {
            throw new IllegalStateException(
                    "REPLAY is not supported on reactive methods: "
                            + method.getDeclaringClass().getName() + "#" + method.getName());
        }
        String key = IdempotencyKeys.resolve(
                idempotent.namespace(), idempotent.key(), method, joinPoint.getArgs(), joinPoint.getTarget());
        Duration inFlight = Duration.ofSeconds(idempotent.inFlightSeconds());
        Duration resultTtl = Duration.ofSeconds(idempotent.resultSeconds());
        Object result = joinPoint.proceed();
        return mono
                ? Mono.defer(() -> guardMono((Mono<?>) result, key, inFlight, resultTtl, idempotent.failOpen()))
                : Flux.defer(() -> guardFlux((Flux<?>) result, key, inFlight, resultTtl, idempotent.failOpen()));
    }

    private Mono<?> guardMono(Mono<?> source, String key, Duration inFlight, Duration resultTtl, boolean failOpen) {
        boolean claimed = claim(key, inFlight, failOpen);
        if (!claimed) {
            return Mono.error(new HyperLlmException(ErrorCode.IDEMPOTENT_IN_FLIGHT));
        }
        return source
                .doOnSuccess(ignored -> complete(key, resultTtl))
                .doOnError(error -> release(key));
    }

    private Flux<?> guardFlux(Flux<?> source, String key, Duration inFlight, Duration resultTtl, boolean failOpen) {
        boolean claimed = claim(key, inFlight, failOpen);
        if (!claimed) {
            return Flux.error(new HyperLlmException(ErrorCode.IDEMPOTENT_IN_FLIGHT));
        }
        return source
                .doOnComplete(() -> complete(key, resultTtl))
                .doOnError(error -> release(key));
    }

    private boolean claim(String key, Duration inFlight, boolean failOpen) {
        try {
            return store.tryAcquire(key, inFlight) == AcquireStatus.ACQUIRED;
        } catch (RuntimeException ex) {
            if (failOpen) {
                log.warn("idempotency store unavailable, letting the call through key={}", key, ex);
                return true;
            }
            throw ex;
        }
    }

    private void complete(String key, Duration resultTtl) {
        try {
            store.complete(key, "{}", resultTtl);
        } catch (RuntimeException ex) {
            log.error("idempotency completion failed key={}", key, ex);
        }
    }

    private void release(String key) {
        try {
            store.release(key);
        } catch (RuntimeException ex) {
            log.error("idempotency release failed key={}", key, ex);
        }
    }
}
