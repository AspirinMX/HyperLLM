import type { FieldErrorItem } from "./types";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldErrorItem[];
  readonly traceId?: string;

  constructor(params: {
    status: number;
    code: string;
    message: string;
    fieldErrors?: FieldErrorItem[];
    traceId?: string;
  }) {
    super(params.message);
    this.name = "ApiError";
    this.status = params.status;
    this.code = params.code;
    this.fieldErrors = params.fieldErrors ?? [];
    this.traceId = params.traceId;
  }
}

const ERROR_MESSAGES: Record<string, string> = {
  AUTH_INVALID_CREDENTIALS: "邮箱或密码不正确",
  AUTH_TOKEN_EXPIRED: "登录已过期，请重新登录",
  AUTH_TOKEN_REUSE_DETECTED: "登录状态异常，请重新登录",
  AUTH_FORBIDDEN: "当前身份无权执行此操作",
  AUTH_INVALID_OTP: "验证码无效或已过期",
  AUTH_OTP_RATE_LIMITED: "发送过于频繁，请稍后再试",
  AUTH_EMAIL_EXISTS: "该邮箱已注册",
  AUTH_IP_BLOCKED: "当前来源地址未在白名单内",
  AUTH_INVALID_KEY: "凭证无效，请刷新后重试",

  TENANT_ORG_NOT_FOUND: "未找到该组织",
  TENANT_ORG_SUSPENDED: "当前组织已被暂停使用",
  TENANT_SLUG_EXISTS: "组织标识已被占用",
  TENANT_PROJECT_NOT_FOUND: "未找到该项目",
  TENANT_PROJECT_DUPLICATE: "该项目名称已存在",
  TENANT_MEMBER_EXISTS: "该成员已在组织中",
  TENANT_INVITATION_INVALID: "邀请链接无效或已过期",

  KEY_NOT_FOUND: "未找到该密钥",
  KEY_ALREADY_REVOKED: "该密钥已吊销",
  KEY_EXPIRED: "该密钥已过期",
  KEY_QUOTA_EXCEEDED: "该密钥额度已用尽",

  MODEL_NOT_FOUND: "未找到该模型",
  MODEL_PRICING_MISSING: "该模型暂未配置定价，暂时无法调用",
  MODEL_NOT_ALLOWED: "当前项目或密钥未授权调用该模型",
  CHANNEL_UNAVAILABLE: "上游模型服务暂时不可用，请稍后重试",
  CHANNEL_CAPACITY_EXCEEDED: "模型通道繁忙，请稍后重试",

  BILLING_INSUFFICIENT_BALANCE: "余额不足，请先充值或核销兑换码",
  BILLING_INVALID_AMOUNT: "请输入有效金额",
  RATE_LIMITED: "请求频率超限，请稍后重试",
  PROJECT_BUDGET_EXCEEDED: "已达到项目当月预算上限",
  PROJECT_BUDGET_UNAVAILABLE: "预算状态正在同步，请稍后重试",
  ANALYTICS_UNAVAILABLE: "用量分析服务暂时不可用，请稍后刷新",
  PROTOCOL_INVALID_REQUEST: "请求参数格式有误",
  UPSTREAM_ERROR: "模型响应异常，请稍后重试",
  CONFLICT_OPTIMISTIC_LOCK: "数据已被他人更新，请刷新后重试",
  LEDGER_BACKLOG: "账务结算繁忙，请稍后重试",
  OUTBOX_UNAVAILABLE: "计费服务繁忙，请稍后重试",
  IDEMPOTENT_IN_FLIGHT: "相同请求正在处理中，请稍候",
  IDEMPOTENT_REPLAYED: "请勿重复提交",

  PAYMENT_ORDER_NOT_FOUND: "未找到该订单",
  PAYMENT_ORDER_CONFLICT: "订单信息已变化，请重新下单",
  PAYMENT_CHANNEL_DISABLED: "当前支付渠道暂未开放",
  PAYMENT_CHANNEL_UNAVAILABLE: "支付渠道响应超时，请稍后重试",
  PAYMENT_VERIFICATION_FAILED: "支付校验失败",
  PAYMENT_AMOUNT_MISMATCH: "支付金额与订单不一致",
  PAYMENT_STATE_CONFLICT: "订单状态已变更，请刷新查看",
  PACK_SKU_NOT_FOUND: "未找到该资源包",
  PACK_SKU_NOT_PURCHASABLE: "该资源包暂不可购买",
  VOUCHER_CODE_INVALID: "兑换码无效或已失效",
  VOUCHER_LIMIT_REACHED: "该活动已达到兑换上限",
  VOUCHER_NOT_FOUND: "未找到对应活动",

  ALERT_RULE_NOT_FOUND: "未找到该告警规则",
  ALERT_RULE_EXISTS: "相同告警规则已存在",
  NOTIFICATION_ENDPOINT_NOT_FOUND: "未找到该通知渠道",
  NOTIFICATION_ENDPOINT_IN_USE: "该通知渠道正被规则引用，请先解绑",

  VALIDATION_ERROR: "请检查填写内容是否完整规范",
  MALFORMED_REQUEST: "提交的数据格式不正确",
  DATA_INTEGRITY_CONFLICT: "数据存在冲突，请刷新后重试",
  BACKEND_UNREACHABLE: "服务连接失败，请确认后台服务已启动",
  GATEWAY_UNREACHABLE: "网关连接失败，请稍后重试",
  NETWORK_ERROR: "网络连接异常，请检查网络后重试",
};

export function getErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.fieldErrors.length > 0) {
      return error.fieldErrors[0].message || ERROR_MESSAGES[error.code] || "操作失败";
    }
    return ERROR_MESSAGES[error.code] ?? "操作失败，请稍后重试";
  }
  if (error instanceof Error && error.message) {
    return "操作失败，请稍后重试";
  }
  return "操作失败，请稍后重试";
}

export function isApiErrorCode(error: unknown, code: string): boolean {
  return error instanceof ApiError && error.code === code;
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export async function parseApiError(response: Response): Promise<ApiError> {
  let code = `HTTP_${response.status}`;
  let message = response.statusText || "Request failed";
  let fieldErrors: FieldErrorItem[] = [];
  let traceId: string | undefined;

  try {
    const raw: unknown = await response.json();
    if (isRecord(raw)) {
      if (typeof raw.code === "string") {
        code = raw.code;
      } else if (isRecord(raw.error) && typeof raw.error.code === "string") {
        code = raw.error.code;
      }

      if (typeof raw.detail === "string" && raw.detail.length > 0) {
        message = raw.detail;
      } else if (typeof raw.message === "string" && raw.message.length > 0) {
        message = raw.message;
      } else if (isRecord(raw.error) && typeof raw.error.message === "string") {
        message = raw.error.message;
      }

      if (typeof raw.traceId === "string") {
        traceId = raw.traceId;
      }

      if (Array.isArray(raw.fieldErrors)) {
        fieldErrors = raw.fieldErrors
          .filter(
            (item): item is FieldErrorItem =>
              isRecord(item) &&
              typeof item.field === "string" &&
              typeof item.message === "string",
          );
      }
    }
  } catch {
    // Non-JSON response body
  }

  return new ApiError({
    status: response.status,
    code,
    message,
    fieldErrors,
    traceId,
  });
}
