package app.pagefault.hyperllm.admin.reconcile;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.repository.ProjectBudgetRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.RECONCILIATION_FINDINGS;

/**
 * The findings book and the operator actions on it.
 *
 * <p>A finding is keyed by organisation and check while it is open: a repeated run refreshes the
 * observation instead of appending another copy, and resolving it is an explicit operator act with
 * a note. An automatic repair is out of scope here: the existing L1-L4 worklist handles events it
 * can prove, and everything else is recorded for a person.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    public record Finding(
            UUID id,
            UUID orgId,
            String checkKind,
            String severity,
            Long expectedMicros,
            Long actualMicros,
            Long deltaMicros,
            String detailJson,
            String state,
            OffsetDateTime detectedAt,
            OffsetDateTime lastSeenAt,
            String resolutionNote
    ) {
    }

    private final DSLContext dsl;
    private final ProjectBudgetRepository budgetRepository;
    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;

    @Transactional
    public void record(UUID orgId, String checkKind, String severity, Long expected, Long actual,
                       Long delta, String detailJson) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        meterRegistry.counter("reconciliation_findings_total", "kind", checkKind, "severity", severity)
                .increment();
        if ("P1".equals(severity)) {
            // P1 is the pager: the difference has no event evidence and a person has to look.
            log.error("P1 reconciliation finding org={} kind={} expected={} actual={} delta={}",
                    orgId, checkKind, expected, actual, delta);
        } else {
            log.warn("Reconciliation finding org={} kind={} delta={}", orgId, checkKind, delta);
        }
        int updated = dsl.update(RECONCILIATION_FINDINGS)
                .set(RECONCILIATION_FINDINGS.SEVERITY, severity)
                .set(RECONCILIATION_FINDINGS.EXPECTED_MICROS, expected)
                .set(RECONCILIATION_FINDINGS.ACTUAL_MICROS, actual)
                .set(RECONCILIATION_FINDINGS.DELTA_MICROS, delta)
                .set(RECONCILIATION_FINDINGS.DETAIL_JSON,
                        detailJson == null ? null : org.jooq.JSONB.valueOf(detailJson))
                .set(RECONCILIATION_FINDINGS.LAST_SEEN_AT, now)
                .where(RECONCILIATION_FINDINGS.ORG_ID.eq(orgId))
                .and(RECONCILIATION_FINDINGS.CHECK_KIND.eq(checkKind))
                .and(RECONCILIATION_FINDINGS.STATE.eq("OPEN"))
                .execute();
        if (updated > 0) {
            return;
        }
        dsl.insertInto(RECONCILIATION_FINDINGS)
                .set(RECONCILIATION_FINDINGS.ID, UuidV7.next())
                .set(RECONCILIATION_FINDINGS.ORG_ID, orgId)
                .set(RECONCILIATION_FINDINGS.CHECK_KIND, checkKind)
                .set(RECONCILIATION_FINDINGS.SEVERITY, severity)
                .set(RECONCILIATION_FINDINGS.EXPECTED_MICROS, expected)
                .set(RECONCILIATION_FINDINGS.ACTUAL_MICROS, actual)
                .set(RECONCILIATION_FINDINGS.DELTA_MICROS, delta)
                .set(RECONCILIATION_FINDINGS.DETAIL_JSON,
                        detailJson == null ? null : org.jooq.JSONB.valueOf(detailJson))
                .set(RECONCILIATION_FINDINGS.STATE, "OPEN")
                .set(RECONCILIATION_FINDINGS.DETECTED_AT, now)
                .set(RECONCILIATION_FINDINGS.LAST_SEEN_AT, now)
                .onConflictDoNothing()
                .execute();
    }

    public List<Finding> list(String state, int limit) {
        String effective = state == null || state.isBlank() ? "OPEN" : state;
        return dsl.selectFrom(RECONCILIATION_FINDINGS)
                .where(RECONCILIATION_FINDINGS.STATE.eq(effective))
                .orderBy(RECONCILIATION_FINDINGS.SEVERITY.asc(),
                        RECONCILIATION_FINDINGS.LAST_SEEN_AT.desc())
                .limit(Math.max(1, Math.min(limit, 200)))
                .fetch(this::toFinding);
    }

    @Transactional
    public Finding resolve(UUID id, String note) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int updated = dsl.update(RECONCILIATION_FINDINGS)
                .set(RECONCILIATION_FINDINGS.STATE, "RESOLVED")
                .set(RECONCILIATION_FINDINGS.RESOLVED_AT, now)
                .set(RECONCILIATION_FINDINGS.RESOLUTION_NOTE,
                        note == null ? null : note.substring(0, Math.min(255, note.length())))
                .where(RECONCILIATION_FINDINGS.ID.eq(id))
                .and(RECONCILIATION_FINDINGS.STATE.eq("OPEN"))
                .execute();
        if (updated != 1) {
            throw new HyperLlmException(ErrorCode.RECONCILIATION_FINDING_NOT_FOUND);
        }
        return dsl.selectFrom(RECONCILIATION_FINDINGS)
                .where(RECONCILIATION_FINDINGS.ID.eq(id))
                .fetchOne(this::toFinding);
    }

    /**
     * Operator resolution of an unverifiable budget month. The live key is removed so the next
     * admission rebuilds the month from the corrected fold instead of trusting the old state.
     */
    @Transactional
    public void resolveBudgetMonth(UUID projectId, LocalDate month, long confirmedMicros) {
        budgetRepository.resolve(projectId, month, confirmedMicros)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.BUDGET_MONTH_NOT_FOUND));
        redis.delete(GatewayRedisKeys.projectBudget(projectId, month.toString().substring(0, 7)));
    }

    private Finding toFinding(Record row) {
        Object detail = row.get(RECONCILIATION_FINDINGS.DETAIL_JSON);
        return new Finding(
                row.get(RECONCILIATION_FINDINGS.ID),
                row.get(RECONCILIATION_FINDINGS.ORG_ID),
                row.get(RECONCILIATION_FINDINGS.CHECK_KIND),
                row.get(RECONCILIATION_FINDINGS.SEVERITY),
                row.get(RECONCILIATION_FINDINGS.EXPECTED_MICROS),
                row.get(RECONCILIATION_FINDINGS.ACTUAL_MICROS),
                row.get(RECONCILIATION_FINDINGS.DELTA_MICROS),
                detail == null ? null : detail.toString(),
                row.get(RECONCILIATION_FINDINGS.STATE),
                row.get(RECONCILIATION_FINDINGS.DETECTED_AT),
                row.get(RECONCILIATION_FINDINGS.LAST_SEEN_AT),
                row.get(RECONCILIATION_FINDINGS.RESOLUTION_NOTE));
    }

    /** Small helper for the job's JSON detail. */
    public static String detail(Map<String, Object> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : new LinkedHashMap<>(values).entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                json.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value);
            } else {
                json.append('"').append(String.valueOf(value).replace("\\", "\\\\")
                        .replace("\"", "\\\"")).append('"');
            }
        }
        return json.append('}').toString();
    }
}
