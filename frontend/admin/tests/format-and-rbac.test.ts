import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  ceilTokenCostMicros,
  formatLocalDateTimeInput,
  formatMicroUsd,
  parseUsdToMicros,
  sanitizeRedirectPath,
} from "../src/lib/format/micro-usd";
import {
  canCommerceWrite,
  canFinanceWrite,
  canOpsWrite,
  formatBpsPercent,
  TRANSLATION_POLICY_LABEL,
  WIRE_PROTOCOL_LABEL,
} from "../src/lib/format/labels";

describe("admin formatting and security utilities", () => {
  it("handles micro-USD integer parsing and ceiling division", () => {
    assert.equal(formatMicroUsd(2_500_000), "$2.50");
    assert.equal(parseUsdToMicros("3.75"), 3_750_000);
    assert.equal(parseUsdToMicros("-1"), null);
    assert.equal(ceilTokenCostMicros(500, 1_000_000), 500);
    assert.equal(ceilTokenCostMicros(1, 500_000), 1);
  });

  it("formats local datetime input and sanitizes redirects", () => {
    const dt = new Date(2026, 10, 24, 18, 30, 0);
    assert.equal(formatLocalDateTimeInput(dt), "2026-11-24T18:30");
    assert.equal(sanitizeRedirectPath("/tenants"), "/tenants");
    assert.equal(sanitizeRedirectPath("//attacker.tld/phish"), "/overview");
    assert.equal(sanitizeRedirectPath("http://attacker.tld"), "/overview");
  });

  it("enforces admin RBAC helpers and V9 protocol labels", () => {
    assert.equal(formatBpsPercent(8500), "85%");
    assert.equal(formatBpsPercent(8550), "85.5%");
    assert.equal(formatBpsPercent(null), "100%");

    assert.equal(canOpsWrite("SUPER_ADMIN"), true);
    assert.equal(canOpsWrite("OPS"), true);
    assert.equal(canOpsWrite("FINANCE"), false);
    assert.equal(canOpsWrite("AUDITOR"), false);

    assert.equal(canFinanceWrite("SUPER_ADMIN"), true);
    assert.equal(canFinanceWrite("FINANCE"), true);
    assert.equal(canFinanceWrite("OPS"), false);

    assert.equal(canCommerceWrite("OPS"), true);
    assert.equal(canCommerceWrite("FINANCE"), true);
    assert.equal(canCommerceWrite("AUDITOR"), false);

    assert.deepEqual(Object.keys(WIRE_PROTOCOL_LABEL).sort(), [
      "ANTHROPIC_MESSAGES",
      "GEMINI_GENERATE_CONTENT",
      "OPENAI_CHAT",
      "OPENAI_RESPONSES",
    ]);
    assert.deepEqual(Object.keys(TRANSLATION_POLICY_LABEL).sort(), [
      "BEST_EFFORT",
      "STRICT",
    ]);
  });
});
