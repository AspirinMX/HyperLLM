/**
 * Pure integer/BigInt-safe micro-USD utilities.
 * 1 USD = 1_000_000 micro-USD.
 */
export const MICROS_PER_USD = 1_000_000;

export function formatMicroUsd(
  micros: number | null | undefined,
  options?: {
    signed?: boolean;
    maxFractionDigits?: number;
    minFractionDigits?: number;
  },
): string {
  if (micros === null || micros === undefined || !Number.isFinite(micros)) {
    return "—";
  }
  const intMicros = Math.trunc(micros);
  const negative = intMicros < 0;
  const abs = Math.abs(intMicros);
  const dollars = Math.floor(abs / MICROS_PER_USD);
  const remainder = abs % MICROS_PER_USD;

  const minDigits = options?.minFractionDigits ?? 2;
  const maxDigits = options?.maxFractionDigits ?? (abs > 0 && abs < 10_000 ? 6 : 4);

  const fracRaw = String(remainder).padStart(6, "0");
  let trimmed = fracRaw.slice(0, maxDigits).replace(/0+$/, "");
  if (trimmed.length < minDigits) {
    trimmed = fracRaw.slice(0, minDigits);
  }

  const intPart = dollars.toLocaleString("en-US");
  const prefix = negative ? "-$" : options?.signed && intMicros > 0 ? "+$" : "$";
  return `${prefix}${intPart}.${trimmed}`;
}

/**
 * Formats micro-USD price per 1M tokens concisely (e.g. "$3.00", "$0.15").
 */
export function formatPerMillionUsd(microsPer1m: number | null | undefined): string {
  if (microsPer1m === null || microsPer1m === undefined) return "—";
  if (microsPer1m === 0) return "$0.00";
  return formatMicroUsd(microsPer1m, { minFractionDigits: 2, maxFractionDigits: 4 });
}

/**
 * Parses a decimal USD input string (e.g. "12.50") into exact integer micro-USD
 * without floating-point rounding drift.
 */
export function parseUsdToMicros(input: string): number | null {
  const trimmed = input.trim();
  if (!trimmed) return null;
  if (!/^\d+(\.\d{0,6})?$/.test(trimmed)) return null;

  const [wholeStr, fracStr = ""] = trimmed.split(".");
  const whole = Number(wholeStr);
  const frac = Number(fracStr.padEnd(6, "0"));
  const result = whole * MICROS_PER_USD + frac;

  if (!Number.isSafeInteger(result)) return null;
  return result;
}

/**
 * Parses a decimal currency string (e.g. "100.50") into exact integer minor units (cents/fen).
 */
export function parseMajorToMinor(input: string): number | null {
  const trimmed = input.trim();
  if (!trimmed) return null;
  if (!/^\d+(\.\d{0,2})?$/.test(trimmed)) return null;

  const [wholeStr, fracStr = ""] = trimmed.split(".");
  const whole = Number(wholeStr);
  const frac = Number(fracStr.padEnd(2, "0"));
  const result = whole * 100 + frac;

  if (!Number.isSafeInteger(result) || result <= 0) return null;
  return result;
}

export function formatMinorCurrency(minor: number, currency: string): string {
  const negative = minor < 0;
  const abs = Math.abs(Math.trunc(minor));
  const major = Math.floor(abs / 100).toLocaleString("en-US");
  const cents = String(abs % 100).padStart(2, "0");
  const symbol = currency === "CNY" ? "¥" : "$";
  return `${negative ? "-" : ""}${symbol}${major}.${cents}`;
}

export function formatCompactNumber(value: number): string {
  if (!Number.isFinite(value)) return "0";
  const abs = Math.abs(value);
  if (abs >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(2)}B`;
  if (abs >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M`;
  if (abs >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
  return Math.trunc(value).toLocaleString("en-US");
}

export function formatLatencyMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms) || ms <= 0) {
    return "—";
  }
  if (ms >= 1000) {
    return `${(ms / 1000).toFixed(2)} s`;
  }
  return `${Math.round(ms)} ms`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

export function formatFullDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

/**
 * Estimates micro-USD cost using integer ceiling division:
 * ceil((tokens * pricePer1M) / 1_000_000).
 */
export function ceilTokenCostMicros(tokens: number, priceMicrosPer1m: number): number {
  if (tokens <= 0 || priceMicrosPer1m <= 0) return 0;
  const prod = BigInt(Math.trunc(tokens)) * BigInt(Math.trunc(priceMicrosPer1m));
  const denom = BigInt(1_000_000);
  return Number((prod + denom - BigInt(1)) / denom);
}

/**
 * Formats a Date or ISO timestamp into a local YYYY-MM-DDTHH:mm string suitable
 * for HTML `<input type="datetime-local">`, avoiding UTC timezone shifts.
 */
export function formatLocalDateTimeInput(
  value: Date | string | null | undefined,
): string {
  if (!value) return "";
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * Prevents open redirects by allowing only same-origin relative paths
 * (rejecting protocol-relative `//...`, backslashes, and external URLs).
 */
export function sanitizeRedirectPath(
  raw: string | null | undefined,
  fallback = "/overview",
): string {
  if (!raw) return fallback;
  const trimmed = raw.trim();
  if (!trimmed.startsWith("/") || trimmed.startsWith("//") || trimmed.includes("\\")) {
    return fallback;
  }
  return trimmed;
}
