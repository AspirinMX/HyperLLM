export type OrgRole = "OWNER" | "ADMIN" | "DEVELOPER" | "BILLING";
export type OrgStatus = "ACTIVE" | "SUSPENDED" | "CLOSED";
export type ProjectEnvironment = "PRODUCTION" | "STAGING" | "DEVELOPMENT";
export type ProjectStatus = "ACTIVE" | "PAUSED" | "ARCHIVED";
export type ApiKeyStatus = "ACTIVE" | "DISABLED" | "REVOKED";
export type InvitationStatus = "PENDING" | "ACCEPTED" | "EXPIRED" | "REVOKED";
export type BillingMode =
  | "TOKEN"
  | "PER_CALL"
  | "IMAGE"
  | "AUDIO_CHAR"
  | "AUDIO_DURATION";
export type ModelCapability =
  | "CHAT"
  | "REASONING"
  | "EMBEDDING"
  | "IMAGE"
  | "AUDIO_TTS"
  | "AUDIO_STT"
  | "RERANK";
export type ProtocolFamily = "OPENAI" | "ANTHROPIC" | "GEMINI" | "COHERE_RERANK";
export type LedgerType =
  | "RECHARGE"
  | "CONSUME"
  | "BONUS_GRANT"
  | "PACK_GRANT"
  | "BONUS_EXPIRE"
  | "REFUND"
  | "ADJUST";
export type OrderType = "WALLET_TOPUP" | "RESOURCE_PACK_PURCHASE";
export type OrderStatus = "PENDING" | "PAID" | "FAILED" | "CLOSED" | "REFUNDED";
export type PaymentChannel = "STRIPE" | "ALIPAY" | "WECHAT_PAY";
export type PackUnitType = "TOKENS" | "CALLS";
export type PackSkuStatus = "DRAFT" | "ACTIVE" | "RETIRED";
export type EntitlementStatus = "ACTIVE" | "EXHAUSTED" | "EXPIRED" | "REVOKED";
export type GrantSource = "SIGNUP" | "REDEMPTION" | "ADMIN_GRANT" | "CAMPAIGN";
export type AlertType = "LOW_BALANCE" | "PROJECT_SOFT_CAP" | "PROJECT_HARD_CAP";
export type NotificationChannelType = "EMAIL" | "WEBHOOK";
export type ToggleStatus = "ACTIVE" | "DISABLED";

export interface PageResponse<T> {
  items: T[];
  page: number;
  pageSize: number;
  total: number;
}

export interface FieldErrorItem {
  field: string;
  message: string;
}

export interface SendEmailCodeRequest {
  email: string;
  purpose?: string;
}

export interface SendEmailCodeResponse {
  sent: boolean;
  expiresInSeconds: number;
  debugCode: string | null;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
  emailCode: string;
  locale?: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface OrgMembershipSummaryDto {
  orgId: string;
  orgName: string;
  slug: string;
  personal: boolean;
  role: OrgRole;
}

export interface UserProfileDto {
  id: string;
  email: string;
  displayName: string;
  locale: string;
  preferredCurrency: string;
  defaultOrgId: string | null;
  organizations: OrgMembershipSummaryDto[];
}

export interface AuthTokenResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: UserProfileDto;
}

export interface CreateOrgRequest {
  name: string;
  slug: string;
}

export interface UpdateOrgRequest {
  name: string;
  version: number;
}

export interface OrgDto {
  id: string;
  name: string;
  slug: string;
  ownerUserId: string;
  tierId: string | null;
  maxConcurrency: number;
  personal: boolean;
  status: OrgStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface OrgMemberDto {
  memberId: string;
  orgId: string;
  userId: string;
  email: string;
  displayName: string;
  role: OrgRole;
  joinedAt: string;
}

export interface CreateInvitationRequest {
  email: string;
  role: OrgRole;
  projectIds?: string[];
}

export interface InvitationResponseDto {
  id: string;
  orgId: string;
  email: string;
  role: OrgRole;
  projectIds: string[];
  status: InvitationStatus;
  expiresAt: string;
  invitationToken: string | null;
}

export interface CreateProjectRequest {
  name: string;
  environment: ProjectEnvironment;
  monthlyHardCapMicros: number | null;
  monthlySoftCapMicros: number | null;
  allowedModels: string[] | null;
  rpmLimit: number | null;
  tpmLimit: number | null;
  maxConcurrency: number | null;
}

export interface UpdateProjectRequest {
  name: string;
  monthlyHardCapMicros: number | null;
  monthlySoftCapMicros: number | null;
  allowedModels: string[] | null;
  rpmLimit: number | null;
  tpmLimit: number | null;
  maxConcurrency: number | null;
  status: ProjectStatus;
  version: number;
}

export interface ProjectDto {
  id: string;
  orgId: string;
  name: string;
  environment: ProjectEnvironment;
  monthlyHardCapMicros: number | null;
  monthlySoftCapMicros: number | null;
  allowedModels: string[] | null;
  rpmLimit: number | null;
  tpmLimit: number | null;
  maxConcurrency: number | null;
  zdrDebugEnabled: boolean;
  zdrDebugTtlDays: number;
  status: ProjectStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectBudgetDto {
  projectId: string;
  month: string;
  hardCapMicros: number | null;
  softCapMicros: number | null;
  usedMicros: number;
  heldMicros: number;
  availableMicros: number | null;
  state: "CONFIRMED" | "UNVERIFIED";
  monthStart: string | null;
  source: "LIVE" | "CONFIRMED";
  observedAt: string;
}

export interface CreateApiKeyRequest {
  name: string;
  allowedModels: string[] | null;
  ipWhitelist: string[] | null;
  rpmLimit: number | null;
  tpmLimit: number | null;
  maxConcurrency: number | null;
  quotaLimitMicros: number | null;
  expiresAt: string | null;
}

export interface UpdateApiKeyRequest extends CreateApiKeyRequest {
  status: ApiKeyStatus;
  version: number;
}

export interface ApiKeyDto {
  id: string;
  projectId: string;
  orgId: string;
  name: string;
  keyPrefix: string;
  allowedModels: string[] | null;
  ipWhitelist: string[] | null;
  rpmLimit: number | null;
  tpmLimit: number | null;
  maxConcurrency: number | null;
  quotaLimitMicros: number | null;
  expiresAt: string | null;
  lastUsedAt: string | null;
  status: ApiKeyStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreatedApiKeyDto {
  apiKey: ApiKeyDto;
  rawKey: string;
}

export interface ActivePricingDto {
  billingMode: BillingMode;
  inputPriceMicros1m: number;
  outputPriceMicros1m: number;
  cacheReadPriceMicros1m: number;
  cacheWritePriceMicros1m: number;
  reasoningPriceMicros1m: number;
  perCallMicros: number;
  effectiveFrom: string;
  effectiveTo: string | null;
}

export interface PublicModelCatalogDto {
  id: string;
  modelCode: string;
  displayName: string;
  providerBadge: string | null;
  capabilityType: ModelCapability;
  nativeProtocol: ProtocolFamily;
  contextWindow: number;
  maxOutputTokens: number | null;
  supportsTools: boolean;
  supportsVision: boolean;
  supportsThinking: boolean;
  supportsPromptCache: boolean;
  pricing: ActivePricingDto | null;
}

export interface WalletDto {
  orgId: string;
  cashBalanceMicros: number;
  bonusBalanceMicros: number;
  heldMicros: number;
  availableMicros: number;
  ledgerConfirmed: boolean;
  totalRechargedMicros: number;
  totalBonusGrantedMicros: number;
  totalConsumedCashMicros: number;
  totalConsumedBonusMicros: number;
  lowBalanceThresholdMicros: number | null;
  version: number;
  updatedAt: string;
}

export interface WalletLedgerDto {
  id: string;
  orgId: string;
  projectId: string | null;
  apiKeyId: string | null;
  ledgerType: LedgerType;
  cashDeltaMicros: number;
  bonusDeltaMicros: number;
  packDeltaUnits: number;
  cashBalanceAfterMicros: number | null;
  bonusBalanceAfterMicros: number | null;
  refRequestId: string | null;
  description: string | null;
  createdAt: string;
}

export interface EphemeralTokenResponse {
  token: string;
  ttlSeconds: number;
  expiresAt: string;
}

export interface CreatePaymentOrderRequest {
  orderType: OrderType;
  paymentChannel: PaymentChannel;
  payAmountMinor?: number | null;
  packSkuId?: string | null;
}

export interface PaymentInstructionDto {
  action: string | null;
  method: string | null;
  fields: Record<string, string> | null;
  redirectUrl: string | null;
  codeUrl: string | null;
}

export interface PaymentOrderDto {
  id: string;
  orderNo: string;
  orderType: OrderType;
  paymentChannel: PaymentChannel;
  payCurrency: string;
  payAmountMinor: number;
  fxRate: string;
  creditMicros: number;
  packSpec: Record<string, unknown> | null;
  paymentStatus: OrderStatus;
  fulfillmentStatus: "NONE" | "PENDING" | "APPLIED";
  fulfillmentEventId: string | null;
  instruction: PaymentInstructionDto | null;
  paidAt: string | null;
  expiresAt: string | null;
  createdAt: string;
}

export interface PackSkuDto {
  id: string;
  code: string;
  name: string;
  unitType: PackUnitType;
  applicableModels: string[];
  totalUnits: number;
  priceMicros: number;
  validDays: number;
  status: PackSkuStatus;
  version: number;
}

export interface RedemptionDto {
  grantEventId: string;
  amountMicros: number;
  applicableModels: string[];
  expiresAt: string | null;
}

export interface ResourcePackDto {
  id: string;
  packName: string;
  unitType: PackUnitType;
  applicableModels: string[];
  totalUnits: number;
  remainingUnits: number;
  expiresAt: string;
  status: EntitlementStatus;
  createdAt: string;
}

export interface BonusGrantDto {
  id: string;
  grantSource: GrantSource;
  applicableModels: string[] | null;
  initialMicros: number;
  remainingMicros: number;
  expiresAt: string | null;
  status: EntitlementStatus;
  createdAt: string;
}

export type UsageGroupBy = "PROJECT" | "API_KEY" | "MODEL" | "CHANNEL" | "HOUR";

export interface UsageRow {
  dimension: string;
  requests: number;
  successes: number;
  promptTokens: number;
  cachedReadTokens: number;
  completionTokens: number;
  reasoningTokens: number;
  costMicros: number;
  cashMicros: number;
  bonusMicros: number;
  upstreamCostMicros: number;
  packUnits: number;
  ttftP50Ms: number;
  ttftP90Ms: number;
  ttftP99Ms: number;
  durationP50Ms: number;
  durationP90Ms: number;
  durationP99Ms: number;
}

export interface UsageTotals {
  requests: number;
  successes: number;
  promptTokens: number;
  cachedReadTokens: number;
  completionTokens: number;
  reasoningTokens: number;
  costMicros: number;
  cashMicros: number;
  bonusMicros: number;
  upstreamCostMicros: number;
  packUnits: number;
}

export interface UsageReport {
  from: string;
  to: string;
  groupBy: UsageGroupBy;
  rows: UsageRow[];
  totals: UsageTotals;
}

export interface CreateEndpointRequest {
  channelType: NotificationChannelType;
  target: string;
}

export interface UpdateEndpointRequest {
  status: ToggleStatus;
  version: number;
}

export interface EndpointDto {
  id: string;
  orgId: string;
  channelType: NotificationChannelType;
  target: string;
  status: ToggleStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreatedEndpointDto {
  endpoint: EndpointDto;
  signingSecret: string | null;
}

export interface CreateAlertRuleRequest {
  alertType: AlertType;
  projectId: string | null;
  thresholdMicros: number;
  endpointIds: string[];
  cooldownMinutes: number | null;
}

export interface AlertRuleDto {
  id: string;
  orgId: string;
  projectId: string | null;
  alertType: AlertType;
  thresholdMicros: number;
  endpointIds: string[];
  cooldownMinutes: number;
  lastTriggeredAt: string | null;
  status: ToggleStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
}
