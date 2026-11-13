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
import { adminAuthApi } from "@/lib/api/endpoints";
import {
  clearAdminSession,
  getAdminTokenState,
  refreshAdminSession,
  setAdminSession,
  subscribeAdminTokenStore,
} from "@/lib/api/token-store";
import type { AdminAuthResponse, AdminProfile } from "@/lib/api/types";

type AdminAuthStatus = "loading" | "authenticated" | "unauthenticated";

interface AdminAuthContextValue {
  status: AdminAuthStatus;
  profile: AdminProfile | null;
  completeAuth: (response: AdminAuthResponse) => void;
  logout: () => Promise<void>;
}

const AdminAuthContext = createContext<AdminAuthContextValue | null>(null);

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AdminAuthStatus>(() =>
    getAdminTokenState().profile ? "authenticated" : "loading",
  );
  const [profile, setProfile] = useState<AdminProfile | null>(
    () => getAdminTokenState().profile,
  );

  useEffect(() => {
    return subscribeAdminTokenStore((next) => {
      setProfile(next.profile);
      if (!next.accessToken && !next.profile) {
        setStatus("unauthenticated");
      } else if (next.profile) {
        setStatus("authenticated");
      }
    });
  }, []);

  useEffect(() => {
    if (getAdminTokenState().profile) return;
    let active = true;

    refreshAdminSession().then((session) => {
      if (!active) return;
      if (session?.accessToken) {
        setProfile({
          adminId: session.adminId,
          username: session.username,
          email: session.email,
          role: session.role,
          mfaVerified: session.mfaVerified,
        });
        setStatus("authenticated");
      } else {
        setStatus("unauthenticated");
      }
    });

    return () => {
      active = false;
    };
  }, []);

  const completeAuth = useCallback((response: AdminAuthResponse) => {
    setAdminSession(response);
    setStatus("authenticated");
  }, []);

  const logout = useCallback(async () => {
    try {
      await adminAuthApi.logout();
    } catch {
      // clear local session regardless
    } finally {
      clearAdminSession();
      setStatus("unauthenticated");
    }
  }, []);

  const value = useMemo(
    () => ({ status, profile, completeAuth, logout }),
    [status, profile, completeAuth, logout],
  );

  return (
    <AdminAuthContext.Provider value={value}>
      {children}
    </AdminAuthContext.Provider>
  );
}

export function useAdminAuth(): AdminAuthContextValue {
  const ctx = useContext(AdminAuthContext);
  if (!ctx) {
    throw new Error("useAdminAuth must be used inside AdminAuthProvider");
  }
  return ctx;
}
