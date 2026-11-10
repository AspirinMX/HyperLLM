"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { authApi, billingApi } from "@/lib/api/endpoints";
import {
  clearAuthSession,
  getTokenState,
  refreshAuthSession,
  setAuthSession,
  setAuthUser,
  subscribeTokenStore,
} from "@/lib/api/token-store";
import type {
  AuthTokenResponse,
  LoginRequest,
  OrgMembershipSummaryDto,
  RegisterRequest,
  UserProfileDto,
  WalletDto,
} from "@/lib/api/types";
import { canViewWallet } from "@/lib/format/labels";

type AuthStatus = "loading" | "authenticated" | "unauthenticated";

interface ConsoleAuthContextValue {
  status: AuthStatus;
  user: UserProfileDto | null;
  currentOrg: OrgMembershipSummaryDto | null;
  wallet: WalletDto | null;
  walletLoading: boolean;
  login: (req: LoginRequest) => Promise<AuthTokenResponse>;
  register: (req: RegisterRequest) => Promise<AuthTokenResponse>;
  logout: () => Promise<void>;
  switchOrg: (orgId: string) => void;
  refreshProfile: () => Promise<UserProfileDto | null>;
  refreshWallet: () => Promise<void>;
}

const ORG_STORAGE_KEY = "hyperllm_console_org_id";

const ConsoleAuthContext = createContext<ConsoleAuthContextValue | null>(null);

function resolveOrgId(
  user: UserProfileDto | null,
  preferred: string | null,
): string | null {
  if (!user || user.organizations.length === 0) return null;
  if (preferred && user.organizations.some((o) => o.orgId === preferred)) {
    return preferred;
  }
  if (
    user.defaultOrgId &&
    user.organizations.some((o) => o.orgId === user.defaultOrgId)
  ) {
    return user.defaultOrgId;
  }
  return user.organizations[0].orgId;
}

function readSavedOrgId(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(ORG_STORAGE_KEY);
}

export function ConsoleAuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>(() =>
    getTokenState().user ? "authenticated" : "loading",
  );
  const [user, setUser] = useState<UserProfileDto | null>(
    () => getTokenState().user,
  );
  const [selectedOrgId, setSelectedOrgId] = useState<string | null>(null);
  const [walletState, setWalletState] = useState<{
    orgId: string | null;
    data: WalletDto | null;
  }>({ orgId: null, data: null });
  const [walletNonce, setWalletNonce] = useState(0);

  useEffect(() => {
    return subscribeTokenStore((next) => {
      setUser(next.user);
      if (!next.accessToken && !next.user) {
        setStatus("unauthenticated");
        setWalletState({ orgId: null, data: null });
      } else if (next.user) {
        setStatus("authenticated");
      }
    });
  }, []);

  useEffect(() => {
    if (getTokenState().user) return;
    let active = true;

    refreshAuthSession().then((session) => {
      if (!active) return;
      if (session?.user) {
        setUser(session.user);
        setSelectedOrgId(resolveOrgId(session.user, readSavedOrgId()));
        setStatus("authenticated");
      } else {
        setStatus("unauthenticated");
      }
    });

    return () => {
      active = false;
    };
  }, []);

  const currentOrg = useMemo(() => {
    if (!user || user.organizations.length === 0) return null;
    const preferred = selectedOrgId ?? readSavedOrgId();
    const targetId = resolveOrgId(user, preferred);
    return (
      user.organizations.find((o) => o.orgId === targetId) ??
      user.organizations[0]
    );
  }, [user, selectedOrgId]);

  const canFetchWallet =
    status === "authenticated" &&
    currentOrg !== null &&
    canViewWallet(currentOrg.role);

  useEffect(() => {
    if (!canFetchWallet || !currentOrg) return;
    let cancelled = false;
    const controller = new AbortController();
    const targetOrgId = currentOrg.orgId;

    billingApi
      .getWallet(targetOrgId, controller.signal)
      .then((next) => {
        if (cancelled) return;
        setWalletState({ orgId: targetOrgId, data: next });
      })
      .catch(() => {
        if (cancelled) return;
        setWalletState({ orgId: targetOrgId, data: null });
      });

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [canFetchWallet, currentOrg, walletNonce]);

  const switchOrg = useCallback((orgId: string) => {
    setSelectedOrgId(orgId);
    if (typeof window !== "undefined") {
      window.localStorage.setItem(ORG_STORAGE_KEY, orgId);
    }
  }, []);

  const refreshWallet = useCallback(async () => {
    setWalletNonce((n) => n + 1);
  }, []);

  const login = useCallback(async (req: LoginRequest) => {
    const res = await authApi.login(req);
    setAuthSession(res);
    setSelectedOrgId(resolveOrgId(res.user, readSavedOrgId()));
    setStatus("authenticated");
    return res;
  }, []);

  const register = useCallback(async (req: RegisterRequest) => {
    const res = await authApi.register(req);
    setAuthSession(res);
    setSelectedOrgId(resolveOrgId(res.user, res.user.defaultOrgId));
    setStatus("authenticated");
    return res;
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // Clear local session regardless of server logout failure
    } finally {
      clearAuthSession();
      setWalletState({ orgId: null, data: null });
      setStatus("unauthenticated");
    }
  }, []);

  const refreshProfile = useCallback(async () => {
    try {
      const profile = await authApi.me();
      setAuthUser(profile);
      setSelectedOrgId((prev) => resolveOrgId(profile, prev ?? readSavedOrgId()));
      return profile;
    } catch {
      return null;
    }
  }, []);

  const wallet =
    canFetchWallet && currentOrg && walletState.orgId === currentOrg.orgId
      ? walletState.data
      : null;
  const walletLoading =
    canFetchWallet && currentOrg !== null && walletState.orgId !== currentOrg.orgId;

  const value = useMemo<ConsoleAuthContextValue>(
    () => ({
      status,
      user,
      currentOrg,
      wallet,
      walletLoading,
      login,
      register,
      logout,
      switchOrg,
      refreshProfile,
      refreshWallet,
    }),
    [
      status,
      user,
      currentOrg,
      wallet,
      walletLoading,
      login,
      register,
      logout,
      switchOrg,
      refreshProfile,
      refreshWallet,
    ],
  );

  return (
    <ConsoleAuthContext.Provider value={value}>
      {children}
    </ConsoleAuthContext.Provider>
  );
}

export function useConsoleAuth(): ConsoleAuthContextValue {
  const ctx = useContext(ConsoleAuthContext);
  if (!ctx) {
    throw new Error("useConsoleAuth must be used inside ConsoleAuthProvider");
  }
  return ctx;
}
