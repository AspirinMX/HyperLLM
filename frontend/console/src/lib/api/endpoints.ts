import { apiRequest, createIdempotencyKey } from "./client";
import type {
  AlertRuleDto,
  ApiKeyDto,
  AuthTokenResponse,
  BonusGrantDto,
  CreateAlertRuleRequest,
  CreateApiKeyRequest,
  CreateEndpointRequest,
  CreateInvitationRequest,
  CreateOrgRequest,
  CreatePaymentOrderRequest,
  CreateProjectRequest,
  CreatedApiKeyDto,
  CreatedEndpointDto,
  EndpointDto,
  EphemeralTokenResponse,
  InvitationResponseDto,
  LoginRequest,
  OrgDto,
  OrgMemberDto,
  OrgRole,
  PackSkuDto,
  PageResponse,
  PaymentOrderDto,
  ProjectBudgetDto,
  ProjectDto,
  PublicModelCatalogDto,
  RedemptionDto,
  RegisterRequest,
  ResourcePackDto,
  SendEmailCodeRequest,
  SendEmailCodeResponse,
  UpdateApiKeyRequest,
  UpdateEndpointRequest,
  UpdateOrgRequest,
  UpdateProjectRequest,
  UsageGroupBy,
  UsageReport,
  UserProfileDto,
  WalletDto,
  WalletLedgerDto,
} from "./types";

function buildQuery(params: Record<string, string | number | undefined | null>): string {
  const qs = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      qs.set(key, String(value));
    }
  }
  const encoded = qs.toString();
  return encoded ? `?${encoded}` : "";
}

export const authApi = {
  sendEmailCode(body: SendEmailCodeRequest) {
    return apiRequest<SendEmailCodeResponse>("/api/v1/auth/email-codes", {
      method: "POST",
      body,
      auth: false,
    });
  },
  register(body: RegisterRequest) {
    return apiRequest<AuthTokenResponse>("/api/v1/auth/register", {
      method: "POST",
      body,
      auth: false,
    });
  },
  login(body: LoginRequest) {
    return apiRequest<AuthTokenResponse>("/api/v1/auth/login", {
      method: "POST",
      body,
      auth: false,
    });
  },
  logout() {
    return apiRequest<void>("/api/v1/auth/logout", {
      method: "POST",
      auth: true,
    });
  },
  me(signal?: AbortSignal) {
    return apiRequest<UserProfileDto>("/api/v1/auth/me", { signal });
  },
};

export const catalogApi = {
  listModels(options?: { auth?: boolean; signal?: AbortSignal }) {
    return apiRequest<PublicModelCatalogDto[]>("/api/v1/models", {
      auth: options?.auth ?? false,
      signal: options?.signal,
    });
  },
};

export const orgApi = {
  list(signal?: AbortSignal) {
    return apiRequest<OrgDto[]>("/api/v1/orgs", { signal });
  },
  create(body: CreateOrgRequest) {
    return apiRequest<OrgDto>("/api/v1/orgs", {
      method: "POST",
      body,
    });
  },
  get(orgId: string, signal?: AbortSignal) {
    return apiRequest<OrgDto>(`/api/v1/orgs/${orgId}`, { signal });
  },
  update(orgId: string, body: UpdateOrgRequest) {
    return apiRequest<OrgDto>(`/api/v1/orgs/${orgId}`, {
      method: "PATCH",
      body,
    });
  },
  listMembers(orgId: string, signal?: AbortSignal) {
    return apiRequest<OrgMemberDto[]>(`/api/v1/orgs/${orgId}/members`, { signal });
  },
  updateMemberRole(orgId: string, memberId: string, role: OrgRole) {
    return apiRequest<OrgMemberDto>(
      `/api/v1/orgs/${orgId}/members/${memberId}`,
      {
        method: "PATCH",
        body: { role },
      },
    );
  },
  removeMember(orgId: string, memberId: string) {
    return apiRequest<void>(`/api/v1/orgs/${orgId}/members/${memberId}`, {
      method: "DELETE",
    });
  },
  createInvitation(orgId: string, body: CreateInvitationRequest) {
    return apiRequest<InvitationResponseDto>(
      `/api/v1/orgs/${orgId}/invitations`,
      {
        method: "POST",
        body,
      },
    );
  },
  acceptInvitation(token: string) {
    return apiRequest<OrgDto>("/api/v1/invitations/accept", {
      method: "POST",
      body: { token },
    });
  },
};

export const projectApi = {
  list(orgId: string, page = 1, pageSize = 50, signal?: AbortSignal) {
    return apiRequest<PageResponse<ProjectDto>>(
      `/api/v1/orgs/${orgId}/projects${buildQuery({ page, pageSize })}`,
      { signal },
    );
  },
  create(orgId: string, body: CreateProjectRequest) {
    return apiRequest<ProjectDto>(`/api/v1/orgs/${orgId}/projects`, {
      method: "POST",
      body,
    });
  },
  get(projectId: string, signal?: AbortSignal) {
    return apiRequest<ProjectDto>(`/api/v1/projects/${projectId}`, { signal });
  },
  update(projectId: string, body: UpdateProjectRequest) {
    return apiRequest<ProjectDto>(`/api/v1/projects/${projectId}`, {
      method: "PATCH",
      body,
    });
  },
  remove(projectId: string, version: number) {
    return apiRequest<void>(
      `/api/v1/projects/${projectId}${buildQuery({ version })}`,
      { method: "DELETE" },
    );
  },
  getBudget(projectId: string, signal?: AbortSignal) {
    return apiRequest<ProjectBudgetDto>(`/api/v1/projects/${projectId}/budget`, {
      signal,
    });
  },
  listMembers(projectId: string, signal?: AbortSignal) {
    return apiRequest<string[]>(`/api/v1/projects/${projectId}/members`, {
      signal,
    });
  },
  replaceMembers(projectId: string, userIds: string[]) {
    return apiRequest<string[]>(`/api/v1/projects/${projectId}/members`, {
      method: "PUT",
      body: { userIds },
    });
  },
};

export const apiKeyApi = {
  list(projectId: string, page = 1, pageSize = 50, signal?: AbortSignal) {
    return apiRequest<PageResponse<ApiKeyDto>>(
      `/api/v1/projects/${projectId}/api-keys${buildQuery({ page, pageSize })}`,
      { signal },
    );
  },
  create(projectId: string, body: CreateApiKeyRequest) {
    return apiRequest<CreatedApiKeyDto>(
      `/api/v1/projects/${projectId}/api-keys`,
      {
        method: "POST",
        body,
      },
    );
  },
  update(keyId: string, body: UpdateApiKeyRequest) {
    return apiRequest<ApiKeyDto>(`/api/v1/api-keys/${keyId}`, {
      method: "PATCH",
      body,
    });
  },
  revoke(keyId: string, version: number) {
    return apiRequest<void>(
      `/api/v1/api-keys/${keyId}${buildQuery({ version })}`,
      { method: "DELETE" },
    );
  },
};

export const billingApi = {
  getWallet(orgId: string, signal?: AbortSignal) {
    return apiRequest<WalletDto>(`/api/v1/orgs/${orgId}/wallet`, { signal });
  },
  listLedger(orgId: string, page = 1, pageSize = 20, signal?: AbortSignal) {
    return apiRequest<PageResponse<WalletLedgerDto>>(
      `/api/v1/orgs/${orgId}/wallet/ledger${buildQuery({ page, pageSize })}`,
      { signal },
    );
  },
  createPaymentOrder(
    orgId: string,
    body: CreatePaymentOrderRequest,
    idempotencyKey = createIdempotencyKey(),
  ) {
    return apiRequest<PaymentOrderDto>(`/api/v1/orgs/${orgId}/payment-orders`, {
      method: "POST",
      body,
      idempotencyKey,
    });
  },
  listPaymentOrders(orgId: string, page = 1, pageSize = 20, signal?: AbortSignal) {
    return apiRequest<PageResponse<PaymentOrderDto>>(
      `/api/v1/orgs/${orgId}/payment-orders${buildQuery({ page, pageSize })}`,
      { signal },
    );
  },
  getPaymentOrder(orgId: string, orderId: string, signal?: AbortSignal) {
    return apiRequest<PaymentOrderDto>(
      `/api/v1/orgs/${orgId}/payment-orders/${orderId}`,
      { signal },
    );
  },
  listPackSkus(orgId: string, signal?: AbortSignal) {
    return apiRequest<PackSkuDto[]>(`/api/v1/orgs/${orgId}/resource-pack-skus`, {
      signal,
    });
  },
  listResourcePacks(orgId: string, page = 1, pageSize = 20, signal?: AbortSignal) {
    return apiRequest<PageResponse<ResourcePackDto>>(
      `/api/v1/orgs/${orgId}/resource-packs${buildQuery({ page, pageSize })}`,
      { signal },
    );
  },
  listBonusGrants(orgId: string, page = 1, pageSize = 20, signal?: AbortSignal) {
    return apiRequest<PageResponse<BonusGrantDto>>(
      `/api/v1/orgs/${orgId}/bonus-grants${buildQuery({ page, pageSize })}`,
      { signal },
    );
  },
  redeemVoucher(orgId: string, code: string) {
    return apiRequest<RedemptionDto>(`/api/v1/orgs/${orgId}/redemptions`, {
      method: "POST",
      body: { code },
    });
  },
};

export const usageApi = {
  query(
    orgId: string,
    params: {
      from: string;
      to: string;
      projectId?: string;
      apiKeyId?: string;
      model?: string;
      groupBy?: UsageGroupBy;
    },
    signal?: AbortSignal,
  ) {
    return apiRequest<UsageReport>(
      `/api/v1/orgs/${orgId}/usage${buildQuery({
        from: params.from,
        to: params.to,
        projectId: params.projectId,
        apiKeyId: params.apiKeyId,
        model: params.model,
        groupBy: params.groupBy ?? "HOUR",
      })}`,
      { signal },
    );
  },
};

export const alertApi = {
  listEndpoints(orgId: string, signal?: AbortSignal) {
    return apiRequest<EndpointDto[]>(
      `/api/v1/orgs/${orgId}/notification-endpoints`,
      { signal },
    );
  },
  createEndpoint(orgId: string, body: CreateEndpointRequest) {
    return apiRequest<CreatedEndpointDto>(
      `/api/v1/orgs/${orgId}/notification-endpoints`,
      { method: "POST", body },
    );
  },
  updateEndpoint(orgId: string, endpointId: string, body: UpdateEndpointRequest) {
    return apiRequest<EndpointDto>(
      `/api/v1/orgs/${orgId}/notification-endpoints/${endpointId}`,
      { method: "PATCH", body },
    );
  },
  deleteEndpoint(orgId: string, endpointId: string, version: number) {
    return apiRequest<void>(
      `/api/v1/orgs/${orgId}/notification-endpoints/${endpointId}${buildQuery({ version })}`,
      { method: "DELETE" },
    );
  },
  listRules(orgId: string, signal?: AbortSignal) {
    return apiRequest<AlertRuleDto[]>(`/api/v1/orgs/${orgId}/alert-rules`, {
      signal,
    });
  },
  createRule(orgId: string, body: CreateAlertRuleRequest) {
    return apiRequest<AlertRuleDto>(`/api/v1/orgs/${orgId}/alert-rules`, {
      method: "POST",
      body,
    });
  },
  deleteRule(orgId: string, ruleId: string, version: number) {
    return apiRequest<void>(
      `/api/v1/orgs/${orgId}/alert-rules/${ruleId}${buildQuery({ version })}`,
      { method: "DELETE" },
    );
  },
};

export const playgroundApi = {
  issueEphemeralToken(projectId: string) {
    return apiRequest<EphemeralTokenResponse>(
      "/api/v1/playground/ephemeral-token",
      {
        method: "POST",
        body: { projectId },
      },
    );
  },
};
