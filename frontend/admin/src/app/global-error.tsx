"use client";

import Link from "next/link";

export default function AdminGlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <html lang="zh-CN">
      <body
        style={{
          margin: 0,
          minHeight: "100vh",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          fontFamily:
            "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
          backgroundColor: "#0a0a0b",
          color: "#fafafa",
          padding: "24px",
        }}
      >
        <div
          style={{
            maxWidth: "420px",
            width: "100%",
            border: "1px solid #27272a",
            borderRadius: "12px",
            backgroundColor: "#111113",
            padding: "24px",
            textAlign: "center",
          }}
        >
          <h1 style={{ margin: "0 0 8px", fontSize: "18px", fontWeight: 600 }}>
            HyperLLM 运营后台发生严重错误
          </h1>
          <p
            style={{
              margin: "0 0 16px",
              fontSize: "13px",
              color: "#a1a1aa",
              lineHeight: 1.5,
            }}
          >
            {error.message || "应用根视图渲染失败，请刷新或重试。"}
          </p>
          {error.digest ? (
            <p
              style={{
                margin: "0 0 16px",
                fontSize: "11px",
                fontFamily: "monospace",
                color: "#71717a",
              }}
            >
              Digest: {error.digest}
            </p>
          ) : null}
          <div style={{ display: "flex", gap: "8px", justifyContent: "center" }}>
            <button
              type="button"
              onClick={reset}
              style={{
                padding: "8px 14px",
                borderRadius: "8px",
                border: "none",
                backgroundColor: "#fafafa",
                color: "#09090b",
                fontSize: "13px",
                fontWeight: 500,
                cursor: "pointer",
              }}
            >
              重新加载
            </button>
            <Link
              href="/overview"
              style={{
                padding: "8px 14px",
                borderRadius: "8px",
                border: "1px solid #27272a",
                color: "#fafafa",
                textDecoration: "none",
                fontSize: "13px",
                fontWeight: 500,
              }}
            >
              返回总览
            </Link>
          </div>
        </div>
      </body>
    </html>
  );
}
