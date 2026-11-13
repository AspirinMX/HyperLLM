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
  AUTH_INVALID_CREDENTIALS: "账号、密码或验证码不正确",
  AUTH_TOKEN_EXPIRED: "登录会话已过期，请重新登录",
  AUTH_MFA_REQUIRED: "请先完成双因素认证",
  AUTH_FORBIDDEN: "当前运营角色无权执行该操作",
  TENANT_ORG_NOT_FOUND: "未找到该租户",
  TENANT_PROJECT_NOT_FOUND: "未找到该项目",
  MODEL_NOT_FOUND: "未找到该虚拟模型",
  MODEL_CODE_EXISTS: "虚拟模型标识已存在",
  CHANNEL_NOT_FOUND: "未找到该上游渠道",
  CHANNEL_NAME_EXISTS: "渠道名称已存在",
  CHANNEL_PROBE_FAILED: "渠道连通性探测失败",
  PRICING_WINDOW_OVERLAP: "定价生效时间窗与已有规则重叠",
  BILLING_INVALID_AMOUNT: "请输入有效金额",
  ANALYTICS_UNAVAILABLE: "分析仓库暂时不可用，请稍后重试",
  CONFLICT_OPTIMISTIC_LOCK: "数据已被其他管理员更新，请刷新后重试",
  IDEMPOTENT_IN_FLIGHT: "相同操作正在处理中",
  IDEMPOTENT_REPLAYED: "重复提交已被拦截",
  PAYMENT_ORDER_NOT_FOUND: "未找到该订单",
  PAYMENT_STATE_CONFLICT: "订单状态不允许该操作",
  PAYMENT_ANOMALY_NOT_FOUND: "未找到该支付异常记录",
  PACK_SKU_NOT_FOUND: "未找到该资源包商品",
  PACK_SKU_CODE_EXISTS: "资源包编码已存在",
  VOUCHER_NOT_FOUND: "未找到该代金券活动",
  ALERT_DELIVERY_NOT_FOUND: "未找到该投递记录",
  RECONCILIATION_FINDING_NOT_FOUND: "未找到该对账异常记录",
  BUDGET_MONTH_NOT_FOUND: "未找到该项目月份预算记录",
  VALIDATION_ERROR: "请检查填写内容是否完整规范",
  DATA_INTEGRITY_CONFLICT: "提交内容与现有记录冲突",
  BACKEND_UNREACHABLE: "运营后端连接失败，请确认服务已启动",
  NETWORK_ERROR: "网络连接异常，请稍后重试",
};

export function getErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.fieldErrors.length > 0) {
      return error.fieldErrors[0].message || ERROR_MESSAGES[error.code] || "操作失败";
    }
    return ERROR_MESSAGES[error.code] ?? "操作失败，请稍后重试";
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
      if (typeof raw.code === "string") code = raw.code;
      if (typeof raw.detail === "string" && raw.detail.length > 0) {
        message = raw.detail;
      } else if (typeof raw.message === "string" && raw.message.length > 0) {
        message = raw.message;
      }
      if (typeof raw.traceId === "string") traceId = raw.traceId;
      if (Array.isArray(raw.fieldErrors)) {
        fieldErrors = raw.fieldErrors.filter(
          (item): item is FieldErrorItem =>
            isRecord(item) &&
            typeof item.field === "string" &&
            typeof item.message === "string",
        );
      }
    }
  } catch {
    // non-json body
  }

  return new ApiError({
    status: response.status,
    code,
    message,
    fieldErrors,
    traceId,
  });
}
