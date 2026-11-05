package app.pagefault.hyperllm.admin.controller;

import app.pagefault.hyperllm.admin.alert.AlertDeliveryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/alerts")
@RequiredArgsConstructor
public class AdminAlertController {

    public record DeliveryDto(
            UUID id,
            UUID ruleId,
            UUID orgId,
            UUID endpointId,
            String alertType,
            UUID projectId,
            String subject,
            String status,
            int attempts,
            OffsetDateTime nextAttemptAt,
            String lastErrorClass
    ) {
    }

    private final AlertDeliveryStore store;

    @GetMapping("/deliveries")
    public List<DeliveryDto> deliveries(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return store.list(status, limit).stream()
                .map(delivery -> new DeliveryDto(delivery.id(), delivery.ruleId(), delivery.orgId(),
                        delivery.endpointId(), delivery.alertType(), delivery.projectId(),
                        delivery.subject(), delivery.status(), delivery.attempts(),
                        delivery.nextAttemptAt(), delivery.lastErrorClass()))
                .toList();
    }

    @PostMapping("/deliveries/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@PathVariable UUID id) {
        if (!store.retry(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a retrying or dead delivery can be re-queued");
        }
    }
}
