export type AdminRole = "SUPER_ADMIN" | "OPS" | "FINANCE" | "AUDITOR";
export type AuthScheme =
  | "BEARER"
  | "X_API_KEY"
  | "X_GOOG_API_KEY"
  | "AZURE_API_KEY"
  | "AWS_SIGV4"
  | "GCP_OAUTH";
export type WireProtocol =
  | "OPENAI_CHAT"
  | "OPENAI_RESPONSES"
  | "ANTHROPIC_MESSAGES"
  | "GEMINI_GENERATE_CONTENT";
export type TranslationPolicy = "BEST_EFFORT" | "STRICT";
export type BillingMode =
  | "TOKEN"
  | "PER_CALL"
  | "IMAGE"
  | "AUDIO_CHAR"
  | "AUDIO_DURATION";
export type BindingStatus = "ACTIVE" | "DISABLED";
export type ChannelStatus = "ACTIVE" | "DISABLED" | "CIRCUIT_OPEN" | "EXHAUSTED";
export type ModelCapability =
  | "CHAT"
  | "REASONING"
  | "EMBEDDING"
  | "IMAGE"
  | "AUDIO_TTS"
  | "AUDIO_STT"
  | "RERANK";
export type ModelStatus = "ACTIVE" | "DEPRECATED" | "DISABLED";
export type ModelVisibility = "PUBLIC" | "RESTRICTED";
export type OrgStatus = "ACTIVE" | "SUSPENDED" | "CLOSED";
export type ProtocolFamily = "OPENAI" | "ANTHROPIC" | "GEMINI" | "COHERE_RERANK";
export type RoutingStrategy = "PRIORITY_WEIGHTED" | "LOWEST_COST" | "TTFT_AWARE";
export type TopupType = "CASH" | "BONUS";
export type OrderType = "WALLET_TOPUP" | "RESOURCE_PACK_PURCHASE";
export type OrderStatus = "PENDING" | "PAID" | "FAILED" | "CLOSED" | "REFUNDED";
export type PaymentChannel = "STRIPE" | "ALIPAY" | "WECHAT_PAY";
export type PackUnitType = "TOKENS" | "CALLS";
export type PackSkuStatus = "DRAFT" | "ACTIVE" | "RETIRED";
export type VoucherType = "SIGNUP_BONUS" | "PROMO_CODE" | "CAMPAIGN";
export type VoucherStatus = "ACTIVE" | "PAUSED" | "ENDED";
export type PaymentAnomalyKind =
  | "SIGNATURE"
  | "AMOUNT_MISMATCH"
  | "CURRENCY_MISMATCH"
  | "ORDER_MISMATCH"
  | "TRANSACTION_CONFLICT"
  | "UNMATCHED_PAYMENT"
  | "CHANNEL_CONFLICT";
export type PaymentAnomalyStatus = "OPEN" | "RESOLVED" | "IGNORED";

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

export interface AdminLoginRequest {
  login: string;
  password: string;
}

export interface AdminAuthResponse {
  mfaRequired: boolean;
  mfaAction: "MFA_SETUP" | "MFA_VERIFY" | null;
  accessToken: string | null;
  challengeToken: string | null;
  expiresInSeconds: number;
  adminId: string;
  username: string;
  email: string;
  role: AdminRole;
  mfaVerified: boolean;
}

export interface MfaSetupChallengeResponse {
  secret: string;
  otpauthUri: string;
}

export interface AdminProfile {
  adminId: string;
  username: string;
  email: string;
  role: AdminRole;
  mfaVerified: boolean;
}

export interface ProviderDto {
  id: string;
  code: string;
  displayName: string;
  protocolFamily: ProtocolFamily;
  defaultBaseUrl: string;
  authScheme: AuthScheme;
  status: string;
  supportedProtocols: WireProtocol[];
  defaultWireProtocol: WireProtocol | null;
}

export interface CreateChannelRequest {
  providerId: string;
  name: string;
  region: string | null;
  baseUrlOverride: string | null;
  apiKey: string;
  defaultCostDiscountBps: number | null;
  rpmLimit: number | null;
  tpmLimit: number | null;
  maxConcurrency: number | null;
  healthCheckModel: string | null;
}

export interface UpdateChannelRequest {
  name: string;
  region: string | null;
  baseUrlOverride: string | null;
  apiKey: string | null;
  defaultCostDiscountBps: number;
  rpmLimit: number | null;
  tpmLimit: number | null;
  maxConcurrency: number | null;
  healthCheckModel: string | null;
  status: ChannelStatus;
  version: number;
}

export interface ChannelDto {
  id: string;
  providerId: string;
  name: string;
  region: string | null;
  baseUrlOverride: string | null;
  hasCredentials: boolean;
  credentialKeyVersion: number;
  defaultCostDiscountBps: number;
  rpmLimit: number | null;
  tpmLimit: number | null;
  maxConcurrency: number | null;
  healthCheckModel: string | null;
  status: ChannelStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelProbeDto {
  channelId: string;
  healthy: boolean;
  httpStatus: number;
  latencyMs: number;
  endpointUrl: string;
  message: string;
}

export interface CreateVirtualModelRequest {
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
  routingStrategy: RoutingStrategy;
  stickyCacheEnabled: boolean;
  stickyTtlSeconds: number;
  visibility: ModelVisibility;
  fallbackModelCode: string | null;
  allowProtocolTranslation?: boolean;
  translationPolicy?: TranslationPolicy;
}

export interface UpdateVirtualModelRequest {
  displayName: string;
  providerBadge: string | null;
  contextWindow: number;
  maxOutputTokens: number | null;
  supportsTools: boolean;
  supportsVision: boolean;
  supportsThinking: boolean;
  supportsPromptCache: boolean;
  routingStrategy: RoutingStrategy;
  stickyCacheEnabled: boolean;
  stickyTtlSeconds: number;
  visibility: ModelVisibility;
  fallbackModelCode: string | null;
  status: ModelStatus;
  allowProtocolTranslation?: boolean;
  translationPolicy?: TranslationPolicy;
  version: number;
}

export interface VirtualModelDto {
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
  routingStrategy: RoutingStrategy;
  stickyCacheEnabled: boolean;
  stickyTtlSeconds: number;
  visibility: ModelVisibility;
  fallbackModelCode: string | null;
  status: ModelStatus;
  allowProtocolTranslation: boolean;
  translationPolicy: TranslationPolicy;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface BindingItemRequest {
  channelId: string;
  upstreamModelName: string;
  upstreamProtocol: WireProtocol | null;
  priorityTier: number;
  weight: number;
  costDiscountBpsOverride: number | null;
  status: BindingStatus;
}

export interface BindingDto {
  id: string;
  virtualModelId: string;
  channelId: string;
  upstreamModelName: string;
  upstreamProtocol: WireProtocol | null;
  priorityTier: number;
  weight: number;
  costDiscountBpsOverride: number | null;
  status: BindingStatus;
  updatedAt: string;
}

export interface PublishPricingRuleRequest {
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

export interface PricingRuleDto {
  id: string;
  virtualModelId: string;
  billingMode: BillingMode;
  inputPriceMicros1m: number;
  outputPriceMicros1m: number;
  cacheReadPriceMicros1m: number;
  cacheWritePriceMicros1m: number;
  reasoningPriceMicros1m: number;
  perCallMicros: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  createdAt: string;
}

export interface AdminOrgDto {
  id: string;
  name: string;
  slug: string;
  ownerUserId: string;
  tierId: string | null;
  discountRatioBps: number | null;
  maxConcurrency: number;
  personal: boolean;
  status: OrgStatus;
  cashBalanceMicros: number | null;
  bonusBalanceMicros: number | null;
  liveDetailAvailable: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdminWalletDetailDto {
  orgId: string;
  cashMicros: number;
  bonusMicros: number;
  heldMicros: number;
  availableMicros: number;
  ledgerConfirmed: boolean;
  totalRechargedMicros: number;
  totalBonusGrantedMicros: number;
  totalConsumedCashMicros: number;
  totalConsumedBonusMicros: number;
}

export interface AdminWalletTopupRequest {
  topupType: TopupType;
  amountMicros: number;
  idempotencyKey: string;
  description: string | null;
}

export interface AdminWalletTopupResponse {
  orgId: string;
  eventId: string;
}

export interface CreatePackSkuRequest {
  code: string;
  name: string;
  unitType: PackUnitType;
  applicableModels: string[];
  totalUnits: number;
  priceMicros: number;
  validDays: number;
  status: PackSkuStatus;
}

export interface UpdatePackSkuRequest {
  name: string;
  unitType: PackUnitType;
  applicableModels: string[];
  totalUnits: number;
  priceMicros: number;
  validDays: number;
  status: PackSkuStatus;
  version: number;
}

export interface PackSkuAdminDto {
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
  createdAt: string;
  updatedAt: string;
}

export interface CreateVoucherRequest {
  name: string;
  voucherType: VoucherType;
  amountMicros: number;
  applicableModels: string[] | null;
  validDays: number | null;
  fixedExpiresAt: string | null;
  maxRedemptionsTotal: number | null;
  maxRedemptionsPerOrg: number;
}

export interface VoucherAdminDto {
  id: string;
  name: string;
  voucherType: VoucherType;
  amountMicros: number;
  applicableModels: string[];
  validDays: number | null;
  fixedExpiresAt: string | null;
  maxRedemptionsTotal: number | null;
  maxRedemptionsPerOrg: number;
  status: VoucherStatus;
  version: number;
  createdAt: string;
}

export interface CodeBatchResponse {
  batchId: string;
  voucherId: string;
  quantity: number;
  codes: string[];
}

export interface PaymentAnomalyDto {
  id: string;
  orgId: string | null;
  orderId: string | null;
  paymentChannel: PaymentChannel;
  kind: PaymentAnomalyKind;
  status: PaymentAnomalyStatus;
  detailJson: string | null;
  createdAt: string;
}

export interface PaymentOrderAdminDto {
  id: string;
  orderNo: string;
  orgId: string;
  orderType: OrderType;
  paymentChannel: PaymentChannel;
  payCurrency: string;
  payAmountMinor: number;
  creditMicros: number;
  status: OrderStatus;
  externalTxId: string | null;
  fulfillmentEventId: string | null;
  paidAt: string | null;
  refundedAt: string | null;
  refundedAmountMinor: number;
  createdAt: string;
}

export interface MarginRow {
  dimension: string;
  requests: number;
  cashMicros: number;
  bonusMicros: number;
  costMicros: number;
  upstreamCostMicros: number;
  contributionMicros: number;
  unknownCostRequests: number;
}

export interface PackSalesRow {
  channel: string;
  currency: string;
  orders: number;
  amountMinor: number;
  creditMicros: number;
}

export interface PackSalesReport {
  from: string;
  to: string;
  paid: PackSalesRow[];
  refunded: PackSalesRow[];
}

export interface RejectRow {
  partition: number;
  offset: number;
  requestId: string;
  errorClass: string;
  detail: string;
  createdAt: string;
}

export interface ReconciliationFinding {
  id: string;
  orgId: string;
  checkKind: string;
  severity: string;
  expectedMicros: number | null;
  actualMicros: number | null;
  deltaMicros: number | null;
  detailJson: string | null;
  state: string;
  detectedAt: string;
  lastSeenAt: string;
  resolutionNote: string | null;
}

export interface AlertDeliveryDto {
  id: string;
  ruleId: string;
  orgId: string;
  endpointId: string;
  alertType: string;
  projectId: string | null;
  subject: string;
  status: string;
  attempts: number;
  nextAttemptAt: string | null;
  lastErrorClass: string | null;
}
