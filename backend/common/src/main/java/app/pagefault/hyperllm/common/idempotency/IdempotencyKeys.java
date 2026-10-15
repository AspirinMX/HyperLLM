package app.pagefault.hyperllm.common.idempotency;

import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an {@link Idempotent#key()} expression against the intercepted call.
 *
 * <p>Expressions may use {@code #p0}, {@code #a0} and, when the build records parameter names,
 * the parameter names themselves. An expression containing neither a hash nor a brace is treated
 * as a literal key.
 */
public final class IdempotencyKeys {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();
    private static final ConcurrentHashMap<String, Expression> CACHE = new ConcurrentHashMap<>();

    private IdempotencyKeys() {
    }

    public static String resolve(String namespace, String expression, Method method, Object[] args, Object target) {
        Objects.requireNonNull(expression, "expression");
        String raw = isLiteral(expression)
                ? expression
                : evaluate(expression, method, args, target);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "Idempotent key expression '" + expression + "' resolved to a blank value on "
                            + method.getDeclaringClass().getSimpleName() + "#" + method.getName());
        }
        return (namespace == null || namespace.isBlank()) ? raw : namespace + ":" + raw;
    }

    private static boolean isLiteral(String expression) {
        return expression.indexOf('#') < 0 && expression.indexOf('{') < 0;
    }

    private static String evaluate(String expression, Method method, Object[] args, Object target) {
        Expression parsed = CACHE.computeIfAbsent(expression, PARSER::parseExpression);
        MethodBasedEvaluationContext context =
                new MethodBasedEvaluationContext(target, method, args, NAME_DISCOVERER);
        Object value = parsed.getValue(context);
        return value == null ? null : String.valueOf(value);
    }
}
