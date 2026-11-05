package app.pagefault.hyperllm.admin.alert;

import app.pagefault.hyperllm.domain.model.billing.BillingAlertRule;
import app.pagefault.hyperllm.domain.model.billing.NotificationEndpoint;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;
import app.pagefault.hyperllm.domain.repository.BillingAlertRuleRepository;
import app.pagefault.hyperllm.domain.repository.NotificationEndpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.BILLING_ALERT_RULES;

/**
 * Turns a sampled condition into one durable delivery per endpoint.
 *
 * <p>The rule is locked before the cooldown is checked again, so two admin instances sampling the
 * same second cannot both notify. The trigger id makes the write idempotent as well: a retry of the
 * same decision cannot create a second delivery for an endpoint.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertTriggerService {

    private final DSLContext dsl;
    private final NotificationEndpointRepository endpointRepository;
    private final AlertDeliveryStore deliveries;

    @Transactional
    public boolean trigger(BillingAlertRule candidate, UUID triggerId, String subject, String payloadJson) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        org.jooq.Record locked = dsl.select(BILLING_ALERT_RULES.LAST_TRIGGERED_AT,
                        BILLING_ALERT_RULES.STATUS, BILLING_ALERT_RULES.DELETED_AT,
                        BILLING_ALERT_RULES.COOLDOWN_MINUTES)
                .from(BILLING_ALERT_RULES)
                .where(BILLING_ALERT_RULES.ID.eq(candidate.id()))
                .forUpdate()
                .fetchOne();
        if (locked == null
                || !ToggleStatus.ACTIVE.name().equals(locked.get(BILLING_ALERT_RULES.STATUS))
                || locked.get(BILLING_ALERT_RULES.DELETED_AT) != null) {
            return false;
        }
        OffsetDateTime lastTriggered = locked.get(BILLING_ALERT_RULES.LAST_TRIGGERED_AT);
        int cooldown = locked.get(BILLING_ALERT_RULES.COOLDOWN_MINUTES);
        if (lastTriggered != null && lastTriggered.plusMinutes(cooldown).isAfter(now)) {
            return false;
        }
        dsl.update(BILLING_ALERT_RULES)
                .set(BILLING_ALERT_RULES.LAST_TRIGGERED_AT, now)
                .set(BILLING_ALERT_RULES.UPDATED_AT, now)
                .where(BILLING_ALERT_RULES.ID.eq(candidate.id()))
                .execute();

        List<NotificationEndpoint> endpoints = endpointRepository
                .listByIds(candidate.endpointIds()).stream()
                .filter(endpoint -> endpoint.orgId().equals(candidate.orgId())
                        && endpoint.status() == ToggleStatus.ACTIVE)
                .toList();
        if (endpoints.isEmpty()) {
            log.warn("Alert rule {} has no active endpoint left", candidate.id());
            return false;
        }
        for (NotificationEndpoint endpoint : endpoints) {
            deliveries.enqueue(candidate.id(), triggerId, candidate.orgId(), endpoint.id(),
                    candidate.alertType().name(), candidate.projectId(), subject, payloadJson, now);
        }
        return true;
    }
}
