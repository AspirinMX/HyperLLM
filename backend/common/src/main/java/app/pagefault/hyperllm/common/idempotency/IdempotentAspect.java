package app.pagefault.hyperllm.common.idempotency;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * Applies {@link Idempotent} to blocking methods.
 *
 * <p>Reactive methods are left untouched here and handled by {@link ReactiveIdempotentAspect}: the
 * claim of a reactive call belongs at subscription time, not at assembly time.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    private final IdempotencyExecutor executor;
    private final ObjectMapper objectMapper;

    public IdempotentAspect(IdempotencyExecutor executor, ObjectMapper objectMapper) {
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        if (isReactive(method)) {
            return joinPoint.proceed();
        }
        String key = IdempotencyKeys.resolve(
                idempotent.namespace(), idempotent.key(), method, joinPoint.getArgs(), joinPoint.getTarget());
        Duration inFlight = Duration.ofSeconds(idempotent.inFlightSeconds());
        Duration resultTtl = Duration.ofSeconds(idempotent.resultSeconds());
        IdempotencyExecutor.Codec<Object> codec = codecFor(method);
        try {
            return executor.execute(key, idempotent.onDuplicate(), inFlight, resultTtl, codec, () -> {
                try {
                    return joinPoint.proceed();
                } catch (RuntimeException | Error ex) {
                    throw ex;
                } catch (Throwable ex) {
                    throw new GuardedInvocationFailure(ex);
                }
            }).value();
        } catch (GuardedInvocationFailure failure) {
            throw failure.getCause();
        } catch (RuntimeException ex) {
            if (idempotent.failOpen() && isStoreFailure(ex)) {
                log.warn("idempotency store unavailable, letting the call through key={}", key, ex);
                return joinPoint.proceed();
            }
            throw ex;
        }
    }

    private IdempotencyExecutor.Codec<Object> codecFor(Method method) {
        if (method.getReturnType() == void.class) {
            return IdempotencyExecutor.Codec.none();
        }
        var javaType = objectMapper.getTypeFactory().constructType(method.getGenericReturnType());
        return new IdempotencyExecutor.Codec<>() {
            @Override
            public String encode(Object value) {
                try {
                    return objectMapper.writeValueAsString(value);
                } catch (Exception ex) {
                    throw new IllegalStateException("cannot encode idempotent result of " + method, ex);
                }
            }

            @Override
            public Object decode(String raw) {
                try {
                    return objectMapper.readValue(raw, javaType);
                } catch (Exception ex) {
                    throw new IllegalStateException("cannot decode idempotent result of " + method, ex);
                }
            }
        };
    }

    private static boolean isReactive(Method method) {
        String name = method.getReturnType().getName();
        return "reactor.core.publisher.Mono".equals(name) || "reactor.core.publisher.Flux".equals(name);
    }

    private static boolean isStoreFailure(RuntimeException ex) {
        return !(ex instanceof HyperLlmException stored
                && (stored.getErrorCode() == ErrorCode.IDEMPOTENT_IN_FLIGHT
                || stored.getErrorCode() == ErrorCode.IDEMPOTENT_REPLAYED));
    }

    /** Carries a checked exception thrown by the guarded method through the supplier boundary. */
    static final class GuardedInvocationFailure extends RuntimeException {
        GuardedInvocationFailure(Throwable cause) {
            super(cause);
        }
    }
}
