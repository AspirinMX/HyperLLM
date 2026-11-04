package app.pagefault.hyperllm.admin.controller;

import app.pagefault.hyperllm.admin.dto.AdminAnalyticsDtos.MarginRow;
import app.pagefault.hyperllm.admin.dto.AdminAnalyticsDtos.PackSalesReport;
import app.pagefault.hyperllm.admin.dto.AdminAnalyticsDtos.RejectRow;
import app.pagefault.hyperllm.admin.service.AdminAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/analytics")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private final AdminAnalyticsService service;

    @GetMapping("/margin")
    public List<MarginRow> margin(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "CHANNEL") String groupBy
    ) {
        return service.margin(from, to, groupBy);
    }

    @GetMapping("/pack-sales")
    public PackSalesReport packSales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to
    ) {
        return service.packSales(from, to);
    }

    @GetMapping("/rejects")
    public List<RejectRow> rejects(@RequestParam(defaultValue = "50") int limit) {
        return service.rejects(limit);
    }
}
