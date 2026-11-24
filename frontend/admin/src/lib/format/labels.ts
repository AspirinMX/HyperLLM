import type {
  AdminRole,
  BillingMode,
  ChannelStatus,
  ModelCapability,
  ModelStatus,
  ModelVisibility,
  OrderStatus,
  OrderType,
  OrgStatus,
  PackSkuStatus,
  PackUnitType,
  PaymentAnomalyKind,
  PaymentAnomalyStatus,
  PaymentChannel,
  ProtocolFamily,
  RoutingStrategy,
  TranslationPolicy,
  VoucherStatus,
  VoucherType,
  WireProtocol,
} from "@/lib/api/types";

export const ADMIN_ROLE_LABEL: Record<AdminRole, string> = {
  SUPER_ADMIN: "超级管理员",
  OPS: "平台运维",
  FINANCE: "财务运营",
  AUDITOR: "审计只读",
};

export const CHANNEL_STATUS_LABEL: Record<ChannelStatus, string> = {
  ACTIVE: "运行中",
  DISABLED: "已停用",
  CIRCUIT_OPEN: "熔断隔离",
  EXHAUSTED: "额度耗尽",
};

export const MODEL_STATUS_LABEL: Record<ModelStatus, string> = {
  ACTIVE: "已上架",
  DEPRECATED: "即将下线",
  DISABLED: "已停用",
};

export const MODEL_VISIBILITY_LABEL: Record<ModelVisibility, string> = {
  PUBLIC: "公开",
  RESTRICTED: "受限",
};

export const ROUTING_STRATEGY_LABEL: Record<RoutingStrategy, string> = {
  PRIORITY_WEIGHTED: "优先级加权",
  LOWEST_COST: "成本最优",
  TTFT_AWARE: "延迟感知",
};

export const PROTOCOL_LABEL: Record<ProtocolFamily, string> = {
  OPENAI: "OpenAI",
  ANTHROPIC: "Anthropic",
  GEMINI: "Gemini",
  COHERE_RERANK: "Rerank",
};

export const WIRE_PROTOCOL_LABEL: Record<WireProtocol, string> = {
  OPENAI_CHAT: "OpenAI Chat",
  OPENAI_RESPONSES: "Responses",
  ANTHROPIC_MESSAGES: "Anthropic",
  GEMINI_GENERATE_CONTENT: "Gemini",
};

export const TRANSLATION_POLICY_LABEL: Record<TranslationPolicy, string> = {
  BEST_EFFORT: "尽力兼容 (Best-Effort)",
  STRICT: "严格校验 (Strict)",
};

export const CAPABILITY_LABEL: Record<ModelCapability, string> = {
  CHAT: "对话",
  REASONING: "推理",
  EMBEDDING: "向量",
  IMAGE: "图像",
  AUDIO_TTS: "语音合成",
  AUDIO_STT: "语音识别",
  RERANK: "重排",
};

export const BILLING_MODE_LABEL: Record<BillingMode, string> = {
  TOKEN: "按 Token",
  PER_CALL: "按次",
  IMAGE: "按张",
  AUDIO_CHAR: "按字符",
  AUDIO_DURATION: "按时长",
};

export const ORG_STATUS_LABEL: Record<OrgStatus, string> = {
  ACTIVE: "正常",
  SUSPENDED: "已停用",
  CLOSED: "已关闭",
};

export const PACK_SKU_STATUS_LABEL: Record<PackSkuStatus, string> = {
  DRAFT: "草稿",
  ACTIVE: "在售",
  RETIRED: "已下架",
};

export const PACK_UNIT_LABEL: Record<PackUnitType, string> = {
  TOKENS: "Token",
  CALLS: "次调用",
};

export const VOUCHER_TYPE_LABEL: Record<VoucherType, string> = {
  SIGNUP_BONUS: "注册赠金",
  PROMO_CODE: "活动码",
  CAMPAIGN: "定向活动",
};

export const VOUCHER_STATUS_LABEL: Record<VoucherStatus, string> = {
  ACTIVE: "进行中",
  PAUSED: "已暂停",
  ENDED: "已结束",
};

export const PAYMENT_CHANNEL_LABEL: Record<PaymentChannel, string> = {
  STRIPE: "Stripe",
  ALIPAY: "支付宝",
  WECHAT_PAY: "微信支付",
};

export const ORDER_TYPE_LABEL: Record<OrderType, string> = {
  WALLET_TOPUP: "余额充值",
  RESOURCE_PACK_PURCHASE: "资源包购买",
};

export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: "待支付",
  PAID: "已支付",
  FAILED: "支付失败",
  CLOSED: "已关闭",
  REFUNDED: "已退款",
};

export const ANOMALY_KIND_LABEL: Record<PaymentAnomalyKind, string> = {
  SIGNATURE: "签名异常",
  AMOUNT_MISMATCH: "金额不符",
  CURRENCY_MISMATCH: "币种不符",
  ORDER_MISMATCH: "订单不匹配",
  TRANSACTION_CONFLICT: "交易号冲突",
  UNMATCHED_PAYMENT: "孤儿收款",
  CHANNEL_CONFLICT: "渠道状态冲突",
};

export const ANOMALY_STATUS_LABEL: Record<PaymentAnomalyStatus, string> = {
  OPEN: "待处理",
  RESOLVED: "已解决",
  IGNORED: "已忽略",
};

export function formatBpsPercent(bps: number | null | undefined): string {
  if (bps === null || bps === undefined) return "100%";
  const pct = bps / 100;
  return Number.isInteger(pct) ? `${pct}%` : `${pct.toFixed(1)}%`;
}

export function canOpsWrite(role: AdminRole | undefined): boolean {
  return role === "SUPER_ADMIN" || role === "OPS";
}

export function canFinanceWrite(role: AdminRole | undefined): boolean {
  return role === "SUPER_ADMIN" || role === "FINANCE";
}

export function canCommerceWrite(role: AdminRole | undefined): boolean {
  return role === "SUPER_ADMIN" || role === "OPS" || role === "FINANCE";
}
