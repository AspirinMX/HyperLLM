package app.pagefault.hyperllm.console.controller;

import app.pagefault.hyperllm.console.dto.UsageDtos.UsageReport;
import app.pagefault.hyperllm.console.service.ConsoleUsageService;
import app.pagefault.hyperllm.console.web.ConsoleRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConsoleUsageController {

    private final ConsoleUsageService service;

    @GetMapping("/orgs/{orgId}/usage")
    public UsageReport usage(
            @PathVariable UUID orgId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID apiKeyId,
            @RequestParam(required = false) String model,
            @RequestParam(defaultValue = "PROJECT") String groupBy,
            HttpServletRequest request
    ) {
        return service.query(orgId, ConsoleRequestContext.requirePrincipal(request).userId(),
                from, to, projectId, apiKeyId, model, groupBy);
    }
}
