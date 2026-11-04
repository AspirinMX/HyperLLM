package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.billing.ProjectBudgetMonth;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Fold of confirmed project spending per UTC month. */
public interface ProjectBudgetRepository {

    Optional<ProjectBudgetMonth> find(UUID projectId, LocalDate month);

    long confirmedMicros(UUID projectId, LocalDate month);

    /** Adds one settlement's rated value. A month already marked unverified stays unverified. */
    void applyCharge(UUID projectId, LocalDate month, long chargeMicros);

    /** Persists that a settlement in this month could not be priced. */
    void markUnverified(UUID projectId, LocalDate month);

    /** Operator resolution: replaces the confirmed value and clears the unverified marker. */
    Optional<ProjectBudgetMonth> resolve(UUID projectId, LocalDate month, long confirmedMicros);

    List<ProjectBudgetMonth> listByProject(UUID projectId, LocalDate fromMonth, LocalDate toMonth);
}
