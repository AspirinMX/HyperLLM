import type { AdminAuthResponse, AdminProfile } from "./types";

interface AdminTokenState {
  accessToken: string | null;
  expiresAtMs: number;
  profile: AdminProfile | null;
}

type Listener = (state: AdminTokenState) => void;

let state: AdminTokenState = {
  accessToken: null,
  expiresAtMs: 0,
  profile: null,
};

const listeners = new Set<Listener>();
let inflightRefresh: Promise<AdminAuthResponse | null> | null = null;

function notify() {
  for (const listener of listeners) {
    listener(state);
  }
}

export function subscribeAdminTokenStore(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getAdminTokenState(): AdminTokenState {
  return state;
}

export function getAdminAccessToken(): string | null {
  return state.accessToken;
}

export function setAdminSession(payload: AdminAuthResponse): void {
  if (!payload.accessToken) return;
  state = {
    accessToken: payload.accessToken,
    expiresAtMs: Date.now() + Math.max(payload.expiresInSeconds - 30, 10) * 1000,
    profile: {
      adminId: payload.adminId,
      username: payload.username,
      email: payload.email,
      role: payload.role,
      mfaVerified: payload.mfaVerified,
    },
  };
  notify();
}

export function clearAdminSession(): void {
  state = {
    accessToken: null,
    expiresAtMs: 0,
    profile: null,
  };
  notify();
}

export async function refreshAdminSession(): Promise<AdminAuthResponse | null> {
  if (inflightRefresh) {
    return inflightRefresh;
  }

  inflightRefresh = (async () => {
    try {
      const response = await fetch("/api/v1/admin/auth/refresh", {
        method: "POST",
        credentials: "include",
        headers: { Accept: "application/json" },
        cache: "no-store",
      });

      if (!response.ok) {
        clearAdminSession();
        return null;
      }

      const data = (await response.json()) as AdminAuthResponse;
      if (data.accessToken) {
        setAdminSession(data);
        return data;
      }
      clearAdminSession();
      return null;
    } catch {
      return null;
    } finally {
      inflightRefresh = null;
    }
  })();

  return inflightRefresh;
}

export async function ensureValidAdminToken(): Promise<string | null> {
  if (state.accessToken && Date.now() < state.expiresAtMs) {
    return state.accessToken;
  }
  const refreshed = await refreshAdminSession();
  return refreshed?.accessToken ?? null;
}

export async function forceRefreshAdminToken(): Promise<string | null> {
  const refreshed = await refreshAdminSession();
  return refreshed?.accessToken ?? null;
}
