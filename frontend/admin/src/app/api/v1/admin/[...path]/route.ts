import { type NextRequest } from "next/server";

const ADMIN_BACKEND_URL =
  process.env.ADMIN_BACKEND_URL ?? "http://localhost:8082";

const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
  "host",
  "content-length",
]);

async function proxyToAdmin(
  request: NextRequest,
  context: { params: Promise<{ path: string[] }> },
): Promise<Response> {
  const { path } = await context.params;
  const search = request.nextUrl.search;
  const targetUrl = `${ADMIN_BACKEND_URL.replace(/\/$/, "")}/api/v1/admin/${path.join("/")}${search}`;

  const headers = new Headers();
  request.headers.forEach((value, key) => {
    if (!HOP_BY_HOP_HEADERS.has(key.toLowerCase())) {
      headers.set(key, value);
    }
  });

  const hasBody = !["GET", "HEAD"].includes(request.method.toUpperCase());
  const body = hasBody ? await request.arrayBuffer() : undefined;

  try {
    const upstream = await fetch(targetUrl, {
      method: request.method,
      headers,
      body: body && body.byteLength > 0 ? body : undefined,
      redirect: "manual",
      cache: "no-store",
    });

    const responseHeaders = new Headers();
    upstream.headers.forEach((value, key) => {
      const lower = key.toLowerCase();
      if (!HOP_BY_HOP_HEADERS.has(lower) && lower !== "set-cookie") {
        responseHeaders.append(key, value);
      }
    });

    const setCookies =
      typeof upstream.headers.getSetCookie === "function"
        ? upstream.headers.getSetCookie()
        : [];
    for (const cookie of setCookies) {
      responseHeaders.append("set-cookie", cookie);
    }

    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: responseHeaders,
    });
  } catch {
    return Response.json(
      {
        status: 502,
        code: "BACKEND_UNREACHABLE",
        title: "Service Unavailable",
        detail: "Admin backend is unreachable",
      },
      { status: 502 },
    );
  }
}

export const GET = proxyToAdmin;
export const POST = proxyToAdmin;
export const PUT = proxyToAdmin;
export const PATCH = proxyToAdmin;
export const DELETE = proxyToAdmin;
