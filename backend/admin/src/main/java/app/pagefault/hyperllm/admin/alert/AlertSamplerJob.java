package app.pagefault.hyperllm.admin.alert;

import app.pagefault.hyperllm.admin.config.AdminProperties;
import app.pagefault.hyperllm.common.billing.LiveWalletReader;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.billing.BillingAlertRule;
import app.pagefault.hyperllm.domain.model.billing.ProjectBudgetMonth;
import app.pagefault.hyperllm.domain.model.enums.AlertType;
import app.pagefault.hyperllm.domain.model.enums.BudgetMonthState;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.repository.BillingAlertRuleRepository;
import app.pagefault.hyperllm.domain.repository.ProjectBudgetRepository;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Samples the three alert conditions and asks {@link AlertTriggerService} to notify.
 *
 * <p>Low balance reads the same live snapshot the gateway spends from. A soft budget reads the live
 * month when it exists and the confirmed fold otherwise, and refuses to judge an unverified month.
 * A hard-cap rule reads the rejection marker the gateway writes: the data plane only records, the
 * control plane decides whether and when a person is told.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertSamplerJob {

    private static final int PAGE = 500;

    private final BillingAlertRuleRepository ruleRepository;
    private final ProjectRepository projectRepository;
    private final ProjectBudgetRepository budgetRepository;
    private final LiveWalletReader liveWalletReader;
    private final StringRedisTemplate redis;
    private final AlertTriggerService triggerService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final AdminProperties properties;

    @Scheduled(fixedDelayString = "${hyperllm.alerts.sampler-delay:PT1M}")
    public void sample() {
        if (!properties.getAlerts().isEnabled()) {
            return;
        }
        sampleOnce();
    }

    public int sampleOnce() {
        int offset = 0;
        int evaluated = 0;
        while (true) {
            List<BillingAlertRule> page = ruleRepository.listActivePage(PAGE, offset);
            if (page.isEmpty()) {
                return evaluated;
            }
            for (BillingAlertRule rule : page) {
                try {
                    evaluate(rule);
                    evaluated++;
                } catch (RuntimeException ex) {
                    meterRegistry.counter("alert_sampling_failures_total",
                            "type", rule.alertType().name()).increment();
                    log.warn("Cannot sample alert rule {}", rule.id(), ex);
                }
            }
            if (page.size() < PAGE) {
                return evaluated;
            }
            offset += PAGE;
        }
    }

    private void evaluate(BillingAlertRule rule) {
        switch (rule.alertType()) {
            case LOW_BALANCE -> evaluateLowBalance(rule);
            case PROJECT_SOFT_CAP -> evaluateSoftCap(rule);
            case PROJECT_HARD_CAP -> evaluateHardCap(rule);
        }
    }

    private void evaluateLowBalance(BillingAlertRule rule) {
        LiveWalletReader.LiveWallet wallet = liveWalletReader.read(rule.orgId());
        if (wallet == null) {
            meterRegistry.counter("alert_sampling_failures_total", "type", "LOW_BALANCE").increment();
            return;
        }
        if (wallet.availableMicros() > rule.thresholdMicros()) {
            return;
        }
        Map<String, Object> payload = base(AlertType.LOW_BALANCE, rule);
        payload.put("availableMicros", wallet.availableMicros());
        payload.put("cashMicros", wallet.cashMicros());
        payload.put("bonusMicros", wallet.bonusMicros());
        payload.put("heldMicros", wallet.heldMicros());
        trigger(rule, "[HyperLLM] Available balance is below the alert threshold", payload);
    }

    private void evaluateSoftCap(BillingAlertRule rule) {
        Project project = rule.projectId() == null ? null
                : projectRepository.findById(rule.projectId()).orElse(null);
        if (project == null || project.monthlySoftCapMicros() == null) {
            return;
        }
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        MonthUsage usage = monthUsage(project.id(), month);
        if (usage == null) {
            return;
        }
        if (usage.usedMicros() < project.monthlySoftCapMicros()) {
            return;
        }
        Map<String, Object> payload = base(AlertType.PROJECT_SOFT_CAP, rule);
        payload.put("projectName", project.name());
        payload.put("month", month.toString());
        payload.put("usedMicros", usage.usedMicros());
        payload.put("softCapMicros", project.monthlySoftCapMicros());
        payload.put("source", usage.source());
        trigger(rule, "[HyperLLM] Project soft budget reached: " + project.name(), payload);
    }

    private void evaluateHardCap(BillingAlertRule rule) {
        Project project = rule.projectId() == null ? null
                : projectRepository.findById(rule.projectId()).orElse(null);
        if (project == null || project.monthlyHardCapMicros() == null) {
            return;
        }
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        String signalKey = GatewayRedisKeys.budgetSignal(project.id(), month.toString());
        Map<Object, Object> signal = redis.opsForHash().entries(signalKey);
        if (signal.isEmpty() || signal.get("last_rejected_at") == null) {
            return;
        }
        String lastRejected = String.valueOf(signal.get("last_rejected_at"));
        String consumed = signal.get("consumed_at") == null ? "" : String.valueOf(signal.get("consumed_at"));
        if (lastRejected.compareTo(consumed) <= 0) {
            return;
        }
        Map<String, Object> payload = base(AlertType.PROJECT_HARD_CAP, rule);
        payload.put("projectName", project.name());
        payload.put("month", month.toString());
        payload.put("hardCapMicros", project.monthlyHardCapMicros());
        payload.put("rejections", signal.get("rejections"));
        payload.put("lastRejectedAt", lastRejected);
        if (trigger(rule, "[HyperLLM] Project hard budget rejected requests: " + project.name(), payload)) {
            // Consuming the marker is what lets the next rejection trigger again after the cooldown.
            redis.opsForHash().put(signalKey, "consumed_at", lastRejected);
        }
    }

    private record MonthUsage(long usedMicros, String source) {
    }

    private MonthUsage monthUsage(java.util.UUID projectId, YearMonth month) {
        Map<Object, Object> live = redis.opsForHash().entries(
                GatewayRedisKeys.projectBudget(projectId, month.toString()));
        if (!live.isEmpty() && live.get("used_micros") != null) {
            String state = live.get("state") == null ? "CONFIRMED" : String.valueOf(live.get("state"));
            if (!BudgetMonthState.CONFIRMED.name().equals(state)) {
                // An unverified month cannot be judged; the failure metric is the operator's signal.
                meterRegistry.counter("alert_sampling_failures_total", "type", "PROJECT_SOFT_CAP").increment();
                return null;
            }
            return new MonthUsage(number(live.get("used_micros")), "LIVE");
        }
        ProjectBudgetMonth fold = budgetRepository.find(projectId, month.atDay(1)).orElse(null);
        if (fold != null && fold.state() != BudgetMonthState.CONFIRMED) {
            meterRegistry.counter("alert_sampling_failures_total", "type", "PROJECT_SOFT_CAP").increment();
            return null;
        }
        return new MonthUsage(fold == null ? 0L : fold.confirmedMicros(), "CONFIRMED");
    }

    private Map<String, Object> base(AlertType type, BillingAlertRule rule) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertType", type.name());
        payload.put("ruleId", rule.id().toString());
        payload.put("orgId", rule.orgId().toString());
        if (rule.projectId() != null) {
            payload.put("projectId", rule.projectId().toString());
        }
        payload.put("thresholdMicros", rule.thresholdMicros());
        payload.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        return payload;
    }

    private boolean trigger(BillingAlertRule rule, String subject, Map<String, Object> payload) {
        try {
            return triggerService.trigger(rule, UuidV7.next(), subject, objectMapper.writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialise an alert payload", ex);
        }
    }

    private static long number(Object value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
