import type { AuthTokenResponse, UserProfileDto } from "./types";

interface TokenState {
  accessToken: string | null;
  expiresAtMs: number;
  user: UserProfileDto | null;
}

type TokenListener = (state: TokenState) => void;

let state: TokenState = {
  accessToken: null,
  expiresAtMs: 0,
  user: null,
};

const listeners = new Set<TokenListener>();
let inflightRefresh: Promise<AuthTokenResponse | null> | null = null;

function notify() {
  for (const listener of listeners) {
    listener(state);
  }
}

export function subscribeTokenStore(listener: TokenListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getTokenState(): TokenState {
  return state;
}

export function getAccessToken(): string | null {
  return state.accessToken;
}

export function setAuthSession(payload: AuthTokenResponse): void {
  state = {
    accessToken: payload.accessToken,
    expiresAtMs: Date.now() + Math.max(payload.expiresInSeconds - 30, 10) * 1000,
    user: payload.user,
  };
  notify();
}

export function setAuthUser(user: UserProfileDto | null): void {
  state = { ...state, user };
  notify();
}

export function clearAuthSession(): void {
  state = {
    accessToken: null,
    expiresAtMs: 0,
    user: null,
  };
  notify();
}

/**
 * Single-flight refresh lock.
 * Backend /api/v1/auth/refresh rotates and immediately revokes the old HttpOnly
 * refresh cookie; concurrent calls MUST share one in-flight promise.
 */
export async function refreshAuthSession(): Promise<AuthTokenResponse | null> {
  if (inflightRefresh) {
    return inflightRefresh;
  }

  inflightRefresh = (async () => {
    try {
      const response = await fetch("/api/v1/auth/refresh", {
        method: "POST",
        credentials: "include",
        headers: {
          Accept: "application/json",
        },
        cache: "no-store",
      });

      if (!response.ok) {
        clearAuthSession();
        return null;
      }

      const data = (await response.json()) as AuthTokenResponse;
      setAuthSession(data);
      return data;
    } catch {
      return null;
    } finally {
      inflightRefresh = null;
    }
  })();

  return inflightRefresh;
}

export async function ensureValidAccessToken(): Promise<string | null> {
  if (state.accessToken && Date.now() < state.expiresAtMs) {
    return state.accessToken;
  }
  const refreshed = await refreshAuthSession();
  return refreshed?.accessToken ?? null;
}

export async function forceRefreshAccessToken(): Promise<string | null> {
  const refreshed = await refreshAuthSession();
  return refreshed?.accessToken ?? null;
}
