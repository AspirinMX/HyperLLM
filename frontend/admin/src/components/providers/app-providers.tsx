"use client";

import type { ReactNode } from "react";
import { AnimateUIProvider } from "@animate-ui/react/provider";
import { MotionConfig } from "motion/react";
import { ThemeProvider } from "next-themes";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { AdminAuthProvider } from "@/components/providers/admin-auth-provider";

export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <ThemeProvider
      attribute="class"
      defaultTheme="system"
      enableSystem
      disableTransitionOnChange
    >
      <MotionConfig reducedMotion="user">
        <AnimateUIProvider reducedMotion="user">
          <TooltipProvider delay={240}>
            <AdminAuthProvider>
              {children}
              <Toaster position="top-right" richColors={false} closeButton />
            </AdminAuthProvider>
          </TooltipProvider>
        </AnimateUIProvider>
      </MotionConfig>
    </ThemeProvider>
  );
}
