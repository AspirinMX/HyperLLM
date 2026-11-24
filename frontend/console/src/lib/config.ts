/**
 * Public-facing gateway base URL displayed in docs and exported code snippets.
 * Override at build/runtime via `NEXT_PUBLIC_GATEWAY_URL`.
 */
export const GATEWAY_PUBLIC_BASE_URL = (
  process.env.NEXT_PUBLIC_GATEWAY_URL ?? "https://api.hyperllm.dev"
).replace(/\/+$/, "");
