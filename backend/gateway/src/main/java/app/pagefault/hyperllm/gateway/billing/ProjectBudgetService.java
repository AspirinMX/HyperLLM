package app.pagefault.hyperllm.gateway.billing;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.domain.model.billing.ProjectBudgetMonth;
import app.pagefault.hyperllm.domain.repository.ProjectBudgetRepository;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Resolves (and, when missing, installs) the budget authority of the current UTC month.
 *
 * <p>The install is deliberately fail-closed: a month can only be recreated from a confirmed fold
 * and an empty set of unconfirmed settlements. Anything else means the gateway cannot prove what
 * the project already spent, and a hard-capped project must answer 503 rather than guess. A project
 * without any configured cap never reaches this service.
 */
@Slf4j
@Service
public class ProjectBudgetService {

    /** One project month, ready to be handed to the admission script. */
    public record BudgetContext(String month, String key, long hardCapMicros, long softCapMicros) {

        public boolean enabled() {
            return month != null;
        }

        public static BudgetContext disabled() {
            return new BudgetContext(null, null, 0L, 0L);
        }
    }

    private final ReactiveStringRedisTemplate redis;
    private final ProjectBudgetRepository repository;
    private final Scheduler domainFallbackScheduler;
    private final RedisScript<String> initScript;
    private final Clock clock;
    private final long ttlMillis;

    @Autowired
    public ProjectBudgetService(
            ReactiveStringRedisTemplate redis,
            ProjectBudgetRepository repository,
            @Qualifier("domainFallbackScheduler") Scheduler domainFallbackScheduler,
            @Qualifier("budgetInitLuaScript") RedisScript<String> initScript,
            GatewayProperties properties
    ) {
        this(redis, repository, domainFallbackScheduler, initScript, Clock.systemUTC(),
                Duration.ofDays(properties.getBilling().getBudgetTtlDays()).toMillis());
    }

    ProjectBudgetService(ReactiveStringRedisTemplate redis, ProjectBudgetRepository repository,
                         Scheduler domainFallbackScheduler, RedisScript<String> initScript,
                         Clock clock, long ttlMillis) {
        this.redis = redis;
        this.repository = repository;
        this.domainFallbackScheduler = domainFallbackScheduler;
        this.initScript = initScript;
        this.clock = clock;
        this.ttlMillis = ttlMillis;
    }

    public Mono<BudgetContext> ensure(UUID orgId, UUID projectId, Long hardCapMicros, Long softCapMicros) {
        if (hardCapMicros == null && softCapMicros == null) {
            return Mono.just(BudgetContext.disabled());
        }
        YearMonth month = YearMonth.now(clock);
        BudgetContext context = new BudgetContext(
                month.toString(),
                GatewayRedisKeys.projectBudget(projectId, month.toString()),
                hardCapMicros == null ? 0L : hardCapMicros,
                softCapMicros == null ? 0L : softCapMicros);
        return redis.hasKey(context.key())
                .defaultIfEmpty(false)
                .flatMap(present -> present ? Mono.just(context) : install(orgId, projectId, month, context));
    }

    /** Called when admission reports the key disappeared between the read and the script. */
    public Mono<BudgetContext> reinstall(UUID orgId, UUID projectId, BudgetContext context) {
        if (!context.enabled()) {
            return Mono.just(context);
        }
        return install(orgId, projectId, YearMonth.parse(context.month()), context);
    }

    public long secondsUntilNextMonth() {
        YearMonth month = YearMonth.now(clock);
        OffsetDateTime next = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        return Math.max(1L, Duration.between(OffsetDateTime.now(clock), next).toSeconds());
    }

    private Mono<BudgetContext> install(UUID orgId, UUID projectId, YearMonth month, BudgetContext context) {
        return redis.opsForSet().size(GatewayRedisKeys.settleWaiting(orgId))
                .defaultIfEmpty(0L)
                .flatMap(pending -> pending > 0L
                        ? Mono.<BudgetContext>error(unavailable("Unconfirmed settlements exist for " + orgId))
                        : loadConfirmed(projectId, month, context));
    }

    private Mono<BudgetContext> loadConfirmed(UUID projectId, YearMonth month, BudgetContext context) {
        return Mono.fromCallable(() -> repository.find(projectId, month.atDay(1)))
                .subscribeOn(domainFallbackScheduler)
                .flatMap(fold -> {
                    long used = fold.map(ProjectBudgetMonth::confirmedMicros).orElse(0L);
                    String state = fold.map(m -> m.state().name()).orElse("CONFIRMED");
                    OffsetDateTime start = fold.map(ProjectBudgetMonth::firstEnabledAt)
                            .orElseGet(() -> month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC));
                    List<String> args = List.of(
                            month.toString(), Long.toString(used), state,
                            Long.toString(context.hardCapMicros()), Long.toString(context.softCapMicros()),
                            start.toString(), Long.toString(ttlMillis));
                    return redis.execute(initScript, List.of(context.key()), args).next()
                            .onErrorResume(ex -> {
                                log.warn("Cannot install budget month {} for project {}", month, projectId, ex);
                                return Mono.error(unavailable("Budget month cannot be installed"));
                            })
                            .thenReturn(context);
                })
                .onErrorResume(ex -> ex instanceof BillingAdmissionException
                        ? Mono.error(ex)
                        : Mono.error(unavailable("Budget fold cannot be read")));
    }

    private static BillingAdmissionException unavailable(String reason) {
        log.warn("Project budget unavailable: {}", reason);
        return new BillingAdmissionException(ErrorCode.PROJECT_BUDGET_UNAVAILABLE, 60L);
    }
}
