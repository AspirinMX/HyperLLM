"use client";

import type { ReactNode } from "react";
import { AnimateUIProvider } from "@animate-ui/react/provider";
import { MotionConfig } from "motion/react";
import { ThemeProvider } from "next-themes";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { ConsoleAuthProvider } from "@/components/providers/console-auth-provider";

interface AppProvidersProps {
  children: ReactNode;
}

export function AppProviders({ children }: AppProvidersProps) {
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
            <ConsoleAuthProvider>
              {children}
              <Toaster position="top-right" richColors={false} closeButton />
            </ConsoleAuthProvider>
          </TooltipProvider>
        </AnimateUIProvider>
      </MotionConfig>
    </ThemeProvider>
  );
}
