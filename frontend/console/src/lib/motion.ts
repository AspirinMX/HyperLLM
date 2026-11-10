import type { Transition, Variants } from "motion/react";

/**
 * HyperLLM unified motion tokens.
 * All UI animations must consume these shared primitives so timing, easing,
 * and reduced-motion fallbacks stay consistent across the console.
 */
export const MOTION_EASE = {
  standard: [0.22, 1, 0.36, 1] as const,
  exit: [0.4, 0, 1, 1] as const,
};

export const MOTION_DURATION = {
  instant: 0.12,
  fast: 0.18,
  base: 0.24,
  slow: 0.34,
} as const;

export const MOTION_SPRING = {
  snappy: {
    type: "spring",
    stiffness: 420,
    damping: 32,
    mass: 0.8,
  } satisfies Transition,
  gentle: {
    type: "spring",
    stiffness: 280,
    damping: 28,
    mass: 0.9,
  } satisfies Transition,
};

export const MOTION_TRANSITION = {
  fade: {
    duration: MOTION_DURATION.fast,
    ease: MOTION_EASE.standard,
  } satisfies Transition,
  panel: {
    duration: MOTION_DURATION.base,
    ease: MOTION_EASE.standard,
  } satisfies Transition,
  layout: MOTION_SPRING.snappy,
};

export const fadeVariants: Variants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: MOTION_TRANSITION.fade,
  },
  exit: {
    opacity: 0,
    transition: { duration: MOTION_DURATION.instant, ease: MOTION_EASE.exit },
  },
};

export const slideUpVariants: Variants = {
  hidden: { opacity: 0, y: 6 },
  visible: {
    opacity: 1,
    y: 0,
    transition: MOTION_TRANSITION.panel,
  },
  exit: {
    opacity: 0,
    y: -4,
    transition: { duration: MOTION_DURATION.instant, ease: MOTION_EASE.exit },
  },
};

export const staggerContainerVariants: Variants = {
  hidden: {},
  visible: {
    transition: {
      staggerChildren: 0.035,
      delayChildren: 0.02,
    },
  },
};
