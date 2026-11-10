import type {
  AlertType,
  ApiKeyStatus,
  LedgerType,
  ModelCapability,
  NotificationChannelType,
  OrderStatus,
  OrderType,
  OrgRole,
  OrgStatus,
  PackUnitType,
  PaymentChannel,
  ProjectEnvironment,
  ProjectStatus,
  ProtocolFamily,
  ToggleStatus,
} from "@/lib/api/types";

export const ORG_ROLE_LABEL: Record<OrgRole, string> = {
  OWNER: "所有者",
  ADMIN: "管理员",
  DEVELOPER: "开发者",
  BILLING: "财务",
};

export const ORG_STATUS_LABEL: Record<OrgStatus, string> = {
  ACTIVE: "正常",
  SUSPENDED: "已停用",
  CLOSED: "已关闭",
};

export const PROJECT_ENV_LABEL: Record<ProjectEnvironment, string> = {
  PRODUCTION: "生产",
  STAGING: "预发",
  DEVELOPMENT: "开发",
};

export const PROJECT_STATUS_LABEL: Record<ProjectStatus, string> = {
  ACTIVE: "运行中",
  PAUSED: "已暂停",
  ARCHIVED: "已归档",
};

export const API_KEY_STATUS_LABEL: Record<ApiKeyStatus, string> = {
  ACTIVE: "启用中",
  DISABLED: "已停用",
  REVOKED: "已吊销",
};

export const PROTOCOL_LABEL: Record<ProtocolFamily, string> = {
  OPENAI: "OpenAI",
  ANTHROPIC: "Anthropic",
  GEMINI: "Gemini",
  COHERE_RERANK: "Rerank",
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

export const LEDGER_TYPE_LABEL: Record<LedgerType, string> = {
  RECHARGE: "在线充值",
  CONSUME: "模型调用",
  BONUS_GRANT: "赠金发放",
  PACK_GRANT: "资源包到账",
  BONUS_EXPIRE: "赠金到期",
  REFUND: "订单退款",
  ADJUST: "账务调整",
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

export const FULFILLMENT_STATUS_LABEL: Record<"NONE" | "PENDING" | "APPLIED", string> = {
  NONE: "未入账",
  PENDING: "入账中",
  APPLIED: "已到账",
};

export const PAYMENT_CHANNEL_LABEL: Record<PaymentChannel, string> = {
  STRIPE: "Stripe",
  ALIPAY: "支付宝",
  WECHAT_PAY: "微信支付",
};

export const PACK_UNIT_LABEL: Record<PackUnitType, string> = {
  TOKENS: "Token",
  CALLS: "次调用",
};

export const ALERT_TYPE_LABEL: Record<AlertType, string> = {
  LOW_BALANCE: "低余额提醒",
  PROJECT_SOFT_CAP: "项目软预算",
  PROJECT_HARD_CAP: "项目硬预算",
};

export const NOTIFICATION_CHANNEL_LABEL: Record<NotificationChannelType, string> = {
  WEBHOOK: "Webhook",
  EMAIL: "邮件",
};

export const TOGGLE_STATUS_LABEL: Record<ToggleStatus, string> = {
  ACTIVE: "启用中",
  DISABLED: "已停用",
};

export function canViewWallet(role: OrgRole | null | undefined): boolean {
  return role === "OWNER" || role === "ADMIN" || role === "BILLING";
}

export function canPayOrRedeem(role: OrgRole | null | undefined): boolean {
  return role === "OWNER" || role === "BILLING";
}

export function canManageOrg(role: OrgRole | null | undefined): boolean {
  return role === "OWNER" || role === "ADMIN";
}

export function canUsePlaygroundOrKeys(role: OrgRole | null | undefined): boolean {
  return role === "OWNER" || role === "ADMIN" || role === "DEVELOPER";
}
