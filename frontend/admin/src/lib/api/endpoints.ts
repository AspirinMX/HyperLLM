import { adminRequest } from "./client";
import type {
  AdminAuthResponse,
  AdminLoginRequest,
  AdminOrgDto,
  AdminWalletDetailDto,
  AdminWalletTopupRequest,
  AdminWalletTopupResponse,
  AlertDeliveryDto,
  BindingDto,
  BindingItemRequest,
  ChannelDto,
  ChannelProbeDto,
  CodeBatchResponse,
  CreateChannelRequest,
  CreatePackSkuRequest,
  CreateVirtualModelRequest,
  CreateVoucherRequest,
  MarginRow,
  MfaSetupChallengeResponse,
  OrgStatus,
  PackSalesReport,
  PackSkuAdminDto,
  PackSkuStatus,
  PageResponse,
  PaymentAnomalyDto,
  PaymentAnomalyStatus,
  PaymentOrderAdminDto,
  PricingRuleDto,
  ProviderDto,
  PublishPricingRuleRequest,
  ReconciliationFinding,
  RejectRow,
  UpdateChannelRequest,
  UpdatePackSkuRequest,
  UpdateVirtualModelRequest,
  VirtualModelDto,
  VoucherAdminDto,
  VoucherStatus,
} from "./types";

function buildQuery(params: Record<string, string | number | undefined | null>): string {
  const qs = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      qs.set(key, String(value));
    }
  }
  const s = qs.toString();
  return s ? `?${s}` : "";
}

export const adminAuthApi = {
  login(body: AdminLoginRequest) {
    return adminRequest<AdminAuthResponse>("/api/v1/admin/auth/login", {
      method: "POST",
      body,
      auth: false,
    });
  },
  initMfaSetup(challengeToken: string) {
    return adminRequest<MfaSetupChallengeResponse>("/api/v1/admin/auth/mfa/setup", {
      method: "POST",
      bearerOverride: challengeToken,
      auth: false,
    });
  },
  completeMfaSetup(challengeToken: string, secret: string, code: string) {
    return adminRequest<AdminAuthResponse>("/api/v1/admin/auth/mfa/setup", {
      method: "POST",
      body: { secret, code },
      bearerOverride: challengeToken,
      auth: false,
    });
  },
  verifyMfa(challengeToken: string, code: string) {
    return adminRequest<AdminAuthResponse>("/api/v1/admin/auth/mfa/verify", {
      method: "POST",
      body: { code },
      bearerOverride: challengeToken,
      auth: false,
    });
  },
  logout() {
    return adminRequest<void>("/api/v1/admin/auth/logout", {
      method: "POST",
      auth: true,
    });
  },
};

export const adminChannelApi = {
  listProviders(signal?: AbortSignal) {
    return adminRequest<ProviderDto[]>("/api/v1/admin/providers", { signal });
  },
  listChannels(page = 1, pageSize = 50, signal?: AbortSignal) {
    return adminRequest<PageResponse<ChannelDto>>(
      `/api/v1/admin/channels${buildQuery({ page, pageSize })}`,
      { signal },
    );
  },
  createChannel(body: CreateChannelRequest) {
    return adminRequest<ChannelDto>("/api/v1/admin/channels", {
      method: "POST",
      body,
    });
  },
  updateChannel(channelId: string, body: UpdateChannelRequest) {
    return adminRequest<ChannelDto>(`/api/v1/admin/channels/${channelId}`, {
      method: "PATCH",
      body,
    });
  },
  deleteChannel(channelId: string, version: number) {
    return adminRequest<void>(
      `/api/v1/admin/channels/${channelId}${buildQuery({ version })}`,
      { method: "DELETE" },
    );
  },
  probeChannel(channelId: string) {
    return adminRequest<ChannelProbeDto>(
      `/api/v1/admin/channels/${channelId}/probe`,
      { method: "POST" },
    );
  },
};

export const adminModelApi = {
  listModels(page = 1, pageSize = 50, signal?: AbortSignal) {
    return adminRequest<PageResponse<VirtualModelDto>>(
      `/api/v1/admin/models${buildQuery({ page, pageSize })}`,
      { signal },
    );
  },
  createModel(body: CreateVirtualModelRequest) {
    return adminRequest<VirtualModelDto>("/api/v1/admin/models", {
      method: "POST",
      body,
    });
  },
  updateModel(modelId: string, body: UpdateVirtualModelRequest) {
    return adminRequest<VirtualModelDto>(`/api/v1/admin/models/${modelId}`, {
      method: "PATCH",
      body,
    });
  },
  deleteModel(modelId: string, version: number) {
    return adminRequest<void>(
      `/api/v1/admin/models/${modelId}${buildQuery({ version })}`,
      { method: "DELETE" },
    );
  },
  listBindings(modelId: string, signal?: AbortSignal) {
    return adminRequest<BindingDto[]>(
      `/api/v1/admin/models/${modelId}/bindings`,
      { signal },
    );
  },
  replaceBindings(modelId: string, bindings: BindingItemRequest[]) {
    return adminRequest<BindingDto[]>(
      `/api/v1/admin/models/${modelId}/bindings`,
      { method: "PUT", body: { bindings } },
    );
  },
  clearBindings(modelId: string) {
    return adminRequest<void>(`/api/v1/admin/models/${modelId}/bindings`, {
      method: "DELETE",
    });
  },
  listPricingRules(modelId: string, signal?: AbortSignal) {
    return adminRequest<PricingRuleDto[]>(
      `/api/v1/admin/models/${modelId}/pricing-rules`,
      { signal },
    );
  },
  publishPricingRule(modelId: string, body: PublishPricingRuleRequest) {
    return adminRequest<PricingRuleDto>(
      `/api/v1/admin/models/${modelId}/pricing-rules`,
      { method: "POST", body },
    );
  },
};

export const adminTenantApi = {
  listOrgs(
    params: { status?: OrgStatus; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ) {
    return adminRequest<PageResponse<AdminOrgDto>>(
      `/api/v1/admin/orgs${buildQuery({
        status: params.status,
        page: params.page ?? 1,
        pageSize: params.pageSize ?? 20,
      })}`,
      { signal },
    );
  },
  getWallet(orgId: string, signal?: AbortSignal) {
    return adminRequest<AdminWalletDetailDto>(
      `/api/v1/admin/orgs/${orgId}/wallet`,
      { signal },
    );
  },
  updateOrgStatus(orgId: string, status: OrgStatus, version: number) {
    return adminRequest<AdminOrgDto>(`/api/v1/admin/orgs/${orgId}/status`, {
      method: "PATCH",
      body: { status, version },
    });
  },
  topupWallet(orgId: string, body: AdminWalletTopupRequest) {
    return adminRequest<AdminWalletTopupResponse>(
      `/api/v1/admin/orgs/${orgId}/wallet/topup`,
      { method: "POST", body },
    );
  },
  listOrders(orgId: string, page = 1, pageSize = 20, signal?: AbortSignal) {
    return adminRequest<PageResponse<PaymentOrderAdminDto>>(
      `/api/v1/admin/orgs/${orgId}/payment-orders${buildQuery({ page, pageSize })}`,
      { signal },
    );
  },
  refundOrder(orderId: string, refundedAmountMinor: number, note: string) {
    return adminRequest<PaymentOrderAdminDto>(
      `/api/v1/admin/payment-orders/${orderId}/refund`,
      { method: "POST", body: { refundedAmountMinor, note } },
    );
  },
};

export const adminCommerceApi = {
  listSkus(
    params: { status?: PackSkuStatus; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ) {
    return adminRequest<PageResponse<PackSkuAdminDto>>(
      `/api/v1/admin/resource-pack-skus${buildQuery({
        status: params.status,
        page: params.page ?? 1,
        pageSize: params.pageSize ?? 50,
      })}`,
      { signal },
    );
  },
  createSku(body: CreatePackSkuRequest) {
    return adminRequest<PackSkuAdminDto>("/api/v1/admin/resource-pack-skus", {
      method: "POST",
      body,
    });
  },
  updateSku(skuId: string, body: UpdatePackSkuRequest) {
    return adminRequest<PackSkuAdminDto>(
      `/api/v1/admin/resource-pack-skus/${skuId}`,
      { method: "PUT", body },
    );
  },
  listVouchers(page = 1, pageSize = 50, signal?: AbortSignal) {
    return adminRequest<PageResponse<VoucherAdminDto>>(
      `/api/v1/admin/vouchers${buildQuery({ page, pageSize })}`,
      { signal },
    );
  },
  createVoucher(body: CreateVoucherRequest) {
    return adminRequest<VoucherAdminDto>("/api/v1/admin/vouchers", {
      method: "POST",
      body,
    });
  },
  updateVoucherStatus(voucherId: string, status: VoucherStatus, version: number) {
    return adminRequest<VoucherAdminDto>(
      `/api/v1/admin/vouchers/${voucherId}/status`,
      { method: "PATCH", body: { status, version } },
    );
  },
  createCodeBatch(voucherId: string, quantity: number) {
    return adminRequest<CodeBatchResponse>(
      `/api/v1/admin/vouchers/${voucherId}/code-batches`,
      { method: "POST", body: { quantity } },
    );
  },
};

export const adminAnalyticsApi = {
  margin(
    params: { from: string; to: string; groupBy?: string },
    signal?: AbortSignal,
  ) {
    return adminRequest<MarginRow[]>(
      `/api/v1/admin/analytics/margin${buildQuery({
        from: params.from,
        to: params.to,
        groupBy: params.groupBy ?? "CHANNEL",
      })}`,
      { signal },
    );
  },
  packSales(params: { from: string; to: string }, signal?: AbortSignal) {
    return adminRequest<PackSalesReport>(
      `/api/v1/admin/analytics/pack-sales${buildQuery(params)}`,
      { signal },
    );
  },
  rejects(limit = 50, signal?: AbortSignal) {
    return adminRequest<RejectRow[]>(
      `/api/v1/admin/analytics/rejects${buildQuery({ limit })}`,
      { signal },
    );
  },
};

export const adminOperationsApi = {
  listFindings(state = "OPEN", limit = 100, signal?: AbortSignal) {
    return adminRequest<ReconciliationFinding[]>(
      `/api/v1/admin/reconciliation/findings${buildQuery({ state, limit })}`,
      { signal },
    );
  },
  resolveFinding(id: string, note: string) {
    return adminRequest<void>(
      `/api/v1/admin/reconciliation/findings/${id}/resolve`,
      { method: "POST", body: { note } },
    );
  },
  runReconciliation() {
    return adminRequest<{ checkedOrganisations: number }>(
      "/api/v1/admin/reconciliation/run",
      { method: "POST" },
    );
  },
  resolveBudgetMonth(
    orgId: string,
    projectId: string,
    month: string,
    confirmedMicros: number,
  ) {
    return adminRequest<void>(
      `/api/v1/admin/reconciliation/orgs/${orgId}/projects/${projectId}/budget/${month}/resolve`,
      { method: "POST", body: { confirmedMicros } },
    );
  },
  listAnomalies(
    params: { status?: PaymentAnomalyStatus; page?: number; pageSize?: number },
    signal?: AbortSignal,
  ) {
    return adminRequest<PageResponse<PaymentAnomalyDto>>(
      `/api/v1/admin/payment-anomalies${buildQuery({
        status: params.status,
        page: params.page ?? 1,
        pageSize: params.pageSize ?? 50,
      })}`,
      { signal },
    );
  },
  resolveAnomaly(
    anomalyId: string,
    status: PaymentAnomalyStatus,
    resolution: string,
  ) {
    return adminRequest<void>(
      `/api/v1/admin/payment-anomalies/${anomalyId}/resolve`,
      { method: "POST", body: { status, resolution } },
    );
  },
  listAlertDeliveries(
    params: { status?: string; limit?: number },
    signal?: AbortSignal,
  ) {
    return adminRequest<AlertDeliveryDto[]>(
      `/api/v1/admin/alerts/deliveries${buildQuery({
        status: params.status,
        limit: params.limit ?? 100,
      })}`,
      { signal },
    );
  },
  retryAlertDelivery(id: string) {
    return adminRequest<void>(`/api/v1/admin/alerts/deliveries/${id}/retry`, {
      method: "POST",
    });
  },
};
