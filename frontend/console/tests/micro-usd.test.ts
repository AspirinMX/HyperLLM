import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  ceilTokenCostMicros,
  formatLocalDateTimeInput,
  formatMicroUsd,
  parseMajorToMinor,
  parseUsdToMicros,
  sanitizeRedirectPath,
} from "../src/lib/format/micro-usd";

describe("console micro-usd and formatting utilities", () => {
  it("formats micro-USD values accurately without float artifacts", () => {
    assert.equal(formatMicroUsd(0), "$0.00");
    assert.equal(formatMicroUsd(1_500_000), "$1.50");
    assert.equal(formatMicroUsd(-2_250_000), "-$2.25");
    assert.equal(formatMicroUsd(500, { signed: true }), "+$0.0005");
    assert.equal(formatMicroUsd(1), "$0.000001");
    assert.equal(formatMicroUsd(null), "—");
    assert.equal(formatMicroUsd(Number.NaN), "—");
  });

  it("parses decimal USD strings to exact integer micro-USD", () => {
    assert.equal(parseUsdToMicros("0"), 0);
    assert.equal(parseUsdToMicros("0.1"), 100_000);
    assert.equal(parseUsdToMicros("19.99"), 19_990_000);
    assert.equal(parseUsdToMicros("0.000001"), 1);
    assert.equal(parseUsdToMicros("12.3456789"), null);
    assert.equal(parseUsdToMicros("-5"), null);
    assert.equal(parseUsdToMicros(""), null);
    assert.equal(parseUsdToMicros("abc"), null);
  });

  it("parses currency major amounts into exact minor units", () => {
    assert.equal(parseMajorToMinor("100.50"), 10050);
    assert.equal(parseMajorToMinor("0.01"), 1);
    assert.equal(parseMajorToMinor("0"), null);
    assert.equal(parseMajorToMinor("-1.00"), null);
    assert.equal(parseMajorToMinor("1.234"), null);
  });

  it("computes token costs using integer ceiling division", () => {
    assert.equal(ceilTokenCostMicros(0, 3_000_000), 0);
    assert.equal(ceilTokenCostMicros(-10, 3_000_000), 0);
    assert.equal(ceilTokenCostMicros(1, 1_000_000), 1);
    assert.equal(ceilTokenCostMicros(1, 1), 1);
    assert.equal(ceilTokenCostMicros(1_000_000, 3_000_000), 3_000_000);
    assert.equal(ceilTokenCostMicros(1_000_001, 3_000_000), 3_000_003);
  });

  it("formats local datetime-local strings without UTC offset drift", () => {
    const localDate = new Date(2026, 4, 7, 9, 5, 0);
    assert.equal(formatLocalDateTimeInput(localDate), "2026-05-07T09:05");
    assert.equal(formatLocalDateTimeInput(null), "");
    assert.equal(formatLocalDateTimeInput("not-a-date"), "");
  });

  it("blocks open redirects while preserving valid relative routes", () => {
    assert.equal(sanitizeRedirectPath("/billing?tab=packs"), "/billing?tab=packs");
    assert.equal(sanitizeRedirectPath("//evil.example.com"), "/overview");
    assert.equal(sanitizeRedirectPath("https://evil.example.com"), "/overview");
    assert.equal(sanitizeRedirectPath("/\\evil.example.com"), "/overview");
    assert.equal(sanitizeRedirectPath("", "/models"), "/models");
    assert.equal(sanitizeRedirectPath(undefined, "/models"), "/models");
  });
});
