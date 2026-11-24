package app.pagefault.hyperllm.console.controller;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.console.dto.BillingDtos.BonusGrantDto;
import app.pagefault.hyperllm.console.dto.BillingDtos.EphemeralTokenRequest;
import app.pagefault.hyperllm.console.dto.BillingDtos.EphemeralTokenResponse;
import app.pagefault.hyperllm.console.dto.BillingDtos.PublicModelCatalogDto;
import app.pagefault.hyperllm.console.dto.BillingDtos.ResourcePackDto;
import app.pagefault.hyperllm.console.dto.BillingDtos.WalletDto;
import app.pagefault.hyperllm.console.dto.BillingDtos.WalletLedgerDto;
import app.pagefault.hyperllm.console.service.ConsoleCatalogAndWalletService;
import app.pagefault.hyperllm.console.web.ConsoleRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConsoleCatalogAndWalletController {

    private final ConsoleCatalogAndWalletService service;

    @GetMapping("/models")
    public List<PublicModelCatalogDto> listModels() {
        return service.listPublicModels();
    }

    @GetMapping("/orgs/{orgId}/wallet")
    public WalletDto getWallet(@PathVariable UUID orgId, HttpServletRequest request) {
        return service.getWallet(orgId, ConsoleRequestContext.requirePrincipal(request).userId());
    }

    @GetMapping("/orgs/{orgId}/wallet/ledger")
    public PageResponse<WalletLedgerDto> listLedger(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        return service.listWalletLedger(
                orgId, ConsoleRequestContext.requirePrincipal(request).userId(), PageQuery.of(page, pageSize)
        );
    }

    @GetMapping("/orgs/{orgId}/resource-packs")
    public PageResponse<ResourcePackDto> listResourcePacks(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        return service.listResourcePacks(
                orgId, ConsoleRequestContext.requirePrincipal(request).userId(), PageQuery.of(page, pageSize)
        );
    }

    @GetMapping("/orgs/{orgId}/bonus-grants")
    public PageResponse<BonusGrantDto> listBonusGrants(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        return service.listBonusGrants(
                orgId, ConsoleRequestContext.requirePrincipal(request).userId(), PageQuery.of(page, pageSize)
        );
    }

    @PostMapping("/playground/ephemeral-token")
    public ResponseEntity<EphemeralTokenResponse> issuePlaygroundToken(
            @Valid @RequestBody EphemeralTokenRequest body,
            HttpServletRequest request
    ) {
        EphemeralTokenResponse response = service.issueSandboxToken(
                ConsoleRequestContext.requirePrincipal(request).userId(), body
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
