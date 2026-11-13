import { ApiError, parseApiError } from "./error";
import {
  clearAdminSession,
  ensureValidAdminToken,
  forceRefreshAdminToken,
  getAdminAccessToken,
} from "./token-store";

export interface AdminRequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  headers?: Record<string, string>;
  auth?: boolean;
  bearerOverride?: string;
  signal?: AbortSignal;
}

export function createIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
}

async function executeFetch(
  path: string,
  options: AdminRequestOptions,
  token: string | null,
): Promise<Response> {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  try {
    return await fetch(path, {
      method: options.method ?? "GET",
      credentials: "include",
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: options.signal,
      cache: "no-store",
    });
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") {
      throw err;
    }
    throw new ApiError({
      status: 0,
      code: "NETWORK_ERROR",
      message: "Network error",
    });
  }
}

export async function adminRequest<T>(
  path: string,
  options: AdminRequestOptions = {},
): Promise<T> {
  const requireAuth = options.auth ?? true;
  let token: string | null = options.bearerOverride ?? null;

  if (!token && requireAuth) {
    token = (await ensureValidAdminToken()) ?? getAdminAccessToken();
  }

  let response = await executeFetch(path, options, token);

  if (response.status === 401 && requireAuth && !options.bearerOverride) {
    const refreshed = await forceRefreshAdminToken();
    if (refreshed) {
      response = await executeFetch(path, options, refreshed);
    } else {
      clearAdminSession();
    }
  }

  if (!response.ok) {
    throw await parseApiError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) {
    return undefined as T;
  }

  return (await response.json()) as T;
}
