package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.model.billing.ProjectBudgetMonth;
import app.pagefault.hyperllm.domain.model.enums.BudgetMonthState;
import app.pagefault.hyperllm.domain.repository.ProjectBudgetRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.PROJECT_BUDGET_MONTHS;

@Repository
@RequiredArgsConstructor
public class JooqProjectBudgetRepository implements ProjectBudgetRepository {

    private final DSLContext dsl;

    @Override
    public Optional<ProjectBudgetMonth> find(UUID projectId, LocalDate month) {
        return dsl.selectFrom(PROJECT_BUDGET_MONTHS)
                .where(PROJECT_BUDGET_MONTHS.PROJECT_ID.eq(projectId)
                        .and(PROJECT_BUDGET_MONTHS.BUDGET_MONTH.eq(month)))
                .fetchOptional(this::toDomain);
    }

    @Override
    public long confirmedMicros(UUID projectId, LocalDate month) {
        Long value = dsl.select(PROJECT_BUDGET_MONTHS.CONFIRMED_MICROS)
                .from(PROJECT_BUDGET_MONTHS)
                .where(PROJECT_BUDGET_MONTHS.PROJECT_ID.eq(projectId)
                        .and(PROJECT_BUDGET_MONTHS.BUDGET_MONTH.eq(month)))
                .fetchOne(PROJECT_BUDGET_MONTHS.CONFIRMED_MICROS);
        return value == null ? 0L : value;
    }

    @Override
    public void applyCharge(UUID projectId, LocalDate month, long chargeMicros) {
        if (chargeMicros <= 0L) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dsl.insertInto(PROJECT_BUDGET_MONTHS)
                .set(PROJECT_BUDGET_MONTHS.PROJECT_ID, projectId)
                .set(PROJECT_BUDGET_MONTHS.BUDGET_MONTH, month)
                .set(PROJECT_BUDGET_MONTHS.CONFIRMED_MICROS, chargeMicros)
                .set(PROJECT_BUDGET_MONTHS.STATE, BudgetMonthState.CONFIRMED.name())
                .set(PROJECT_BUDGET_MONTHS.FIRST_ENABLED_AT, now)
                .set(PROJECT_BUDGET_MONTHS.VERSION, 1L)
                .set(PROJECT_BUDGET_MONTHS.UPDATED_AT, now)
                .onConflict(PROJECT_BUDGET_MONTHS.PROJECT_ID, PROJECT_BUDGET_MONTHS.BUDGET_MONTH)
                .doUpdate()
                .set(PROJECT_BUDGET_MONTHS.CONFIRMED_MICROS,
                        PROJECT_BUDGET_MONTHS.CONFIRMED_MICROS.plus(chargeMicros))
                .set(PROJECT_BUDGET_MONTHS.VERSION, PROJECT_BUDGET_MONTHS.VERSION.plus(1L))
                .set(PROJECT_BUDGET_MONTHS.UPDATED_AT, now)
                .execute();
    }

    @Override
    public void markUnverified(UUID projectId, LocalDate month) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dsl.insertInto(PROJECT_BUDGET_MONTHS)
                .set(PROJECT_BUDGET_MONTHS.PROJECT_ID, projectId)
                .set(PROJECT_BUDGET_MONTHS.BUDGET_MONTH, month)
                .set(PROJECT_BUDGET_MONTHS.CONFIRMED_MICROS, 0L)
                .set(PROJECT_BUDGET_MONTHS.STATE, BudgetMonthState.UNVERIFIED.name())
                .set(PROJECT_BUDGET_MONTHS.FIRST_ENABLED_AT, now)
                .set(PROJECT_BUDGET_MONTHS.VERSION, 1L)
                .set(PROJECT_BUDGET_MONTHS.UPDATED_AT, now)
                .onConflict(PROJECT_BUDGET_MONTHS.PROJECT_ID, PROJECT_BUDGET_MONTHS.BUDGET_MONTH)
                .doUpdate()
                .set(PROJECT_BUDGET_MONTHS.STATE, BudgetMonthState.UNVERIFIED.name())
                .set(PROJECT_BUDGET_MONTHS.VERSION, PROJECT_BUDGET_MONTHS.VERSION.plus(1L))
                .set(PROJECT_BUDGET_MONTHS.UPDATED_AT, now)
                .execute();
    }

    @Override
    public Optional<ProjectBudgetMonth> resolve(UUID projectId, LocalDate month, long confirmedMicros) {
        if (confirmedMicros < 0L) {
            throw new IllegalArgumentException("A confirmed project budget cannot be negative");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return dsl.insertInto(PROJECT_BUDGET_MONTHS)
                .set(PROJECT_BUDGET_MONTHS.PROJECT_ID, projectId)
                .set(PROJECT_BUDGET_MONTHS.BUDGET_MONTH, month)
                .set(PROJECT_BUDGET_MONTHS.CONFIRMED_MICROS, confirmedMicros)
                .set(PROJECT_BUDGET_MONTHS.STATE, BudgetMonthState.CONFIRMED.name())
                .set(PROJECT_BUDGET_MONTHS.FIRST_ENABLED_AT, now)
                .set(PROJECT_BUDGET_MONTHS.VERSION, 1L)
                .set(PROJECT_BUDGET_MONTHS.UPDATED_AT, now)
                .onConflict(PROJECT_BUDGET_MONTHS.PROJECT_ID, PROJECT_BUDGET_MONTHS.BUDGET_MONTH)
                .doUpdate()
                .set(PROJECT_BUDGET_MONTHS.CONFIRMED_MICROS, confirmedMicros)
                .set(PROJECT_BUDGET_MONTHS.STATE, BudgetMonthState.CONFIRMED.name())
                .set(PROJECT_BUDGET_MONTHS.VERSION, PROJECT_BUDGET_MONTHS.VERSION.plus(1L))
                .set(PROJECT_BUDGET_MONTHS.UPDATED_AT, now)
                .returning()
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<ProjectBudgetMonth> listByProject(UUID projectId, LocalDate fromMonth, LocalDate toMonth) {
        return dsl.selectFrom(PROJECT_BUDGET_MONTHS)
                .where(PROJECT_BUDGET_MONTHS.PROJECT_ID.eq(projectId)
                        .and(PROJECT_BUDGET_MONTHS.BUDGET_MONTH.between(fromMonth, toMonth)))
                .orderBy(PROJECT_BUDGET_MONTHS.BUDGET_MONTH.desc())
                .fetch(this::toDomain);
    }

    private ProjectBudgetMonth toDomain(Record record) {
        return new ProjectBudgetMonth(
                record.get(PROJECT_BUDGET_MONTHS.PROJECT_ID),
                record.get(PROJECT_BUDGET_MONTHS.BUDGET_MONTH),
                record.get(PROJECT_BUDGET_MONTHS.CONFIRMED_MICROS),
                PgJooqSupport.toEnum(BudgetMonthState.class, record.get(PROJECT_BUDGET_MONTHS.STATE)),
                record.get(PROJECT_BUDGET_MONTHS.FIRST_ENABLED_AT),
                record.get(PROJECT_BUDGET_MONTHS.VERSION),
                record.get(PROJECT_BUDGET_MONTHS.UPDATED_AT)
        );
    }
}
