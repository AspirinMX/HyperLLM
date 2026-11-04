package app.pagefault.hyperllm.console.service;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.console.dto.BudgetDtos.ProjectBudgetDto;
import app.pagefault.hyperllm.domain.model.billing.ProjectBudgetMonth;
import app.pagefault.hyperllm.domain.model.enums.BudgetMonthState;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.repository.ProjectBudgetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Reads one project month for the console.
 *
 * <p>The live hash is preferred while it exists: it already includes settlements the fold has not
 * seen. When it is absent the confirmed fold is shown with its source, so an operator can tell a
 * trailing number from a real one instead of assuming they are the same.
 */
@Service
@RequiredArgsConstructor
public class ConsoleBudgetService {

    private final ConsoleRbacService rbac;
    private final StringRedisTemplate redis;
    private final ProjectBudgetRepository budgetRepository;

    public ProjectBudgetDto getCurrentMonth(UUID projectId, UUID userId) {
        Project project = rbac.requireProjectAccess(projectId, userId, false).project();
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        OffsetDateTime monthStart = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        Map<Object, Object> live = redis.opsForHash().entries(
                GatewayRedisKeys.projectBudget(projectId, month.toString()));
        if (!live.isEmpty() && live.get("used_micros") != null) {
            long used = number(live.get("used_micros"));
            long held = number(live.get("held_micros"));
            String state = live.get("state") == null ? "CONFIRMED" : String.valueOf(live.get("state"));
            OffsetDateTime start = live.get("start_at") == null
                    ? monthStart
                    : OffsetDateTime.parse(String.valueOf(live.get("start_at")));
            return dto(project, month, used, held, state, start, "LIVE");
        }
        ProjectBudgetMonth fold = budgetRepository.find(projectId, month.atDay(1)).orElse(null);
        long used = fold == null ? 0L : fold.confirmedMicros();
        String state = fold == null ? BudgetMonthState.CONFIRMED.name() : fold.state().name();
        OffsetDateTime start = fold == null || fold.firstEnabledAt() == null ? monthStart : fold.firstEnabledAt();
        return dto(project, month, used, 0L, state, start, "CONFIRMED");
    }

    private static ProjectBudgetDto dto(Project project, YearMonth month, long used, long held,
                                        String state, OffsetDateTime start, String source) {
        Long cap = project.monthlyHardCapMicros() != null
                ? project.monthlyHardCapMicros()
                : project.monthlySoftCapMicros();
        Long available = cap == null ? null : Math.max(0L, cap - used - held);
        return new ProjectBudgetDto(project.id(), month.toString(), project.monthlyHardCapMicros(),
                project.monthlySoftCapMicros(), used, held, available, state, start, source,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static long number(Object value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
