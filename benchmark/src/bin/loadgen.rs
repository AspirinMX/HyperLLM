//! High-throughput, zero-GC closed-loop SSE/JSON load generator for HyperLLM Gateway.
//!
//! Drop-in replacement for the legacy Go `loadgen`:
//! - Identical CLI flags, JSON payload generator, TTFT/TPOT/StreamTPS/in-flight math, stdout
//!   summary format, and `-json-out` schema (100% compatible with `run-suite.sh`).
//! - Uses a single-threaded Tokio event loop when pinned to 1 CPU (`taskset -c 7`), eliminating
//!   cross-thread scheduling atomics and work-stealing overhead.
//! - Each closed-loop worker holds one persistent keep-alive `TcpStream`, reuses a pre-built
//!   raw HTTP/1.1 request buffer (`Arc<[u8]>`), and parses chunked/SSE responses in-place into a
//!   small reusable 8 KiB buffer with SIMD `memchr::memmem`.

use bytes::BytesMut;
use hyperllm_bench::Flags;
use memchr::memmem::Finder;
use serde::Serialize;
use std::collections::BTreeMap;
use std::fs::File;
use std::io;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::Instant;

#[derive(Clone)]
struct Config {
    target: String,
    protocol: String,
    model: String,
    api_key: String,
    stream: bool,
    payload_tokens: usize,
    max_tokens: usize,
    concurrency: usize,
    ramp: Duration,
    duration: Duration,
    timeout: Duration,
    tokens_per_frame: usize,
    json_out: String,
    label: String,
}

#[derive(Debug, Default, Serialize)]
struct Percentiles {
    min: f64,
    mean: f64,
    p50: f64,
    p90: f64,
    p95: f64,
    p99: f64,
    max: f64,
}

#[derive(Debug, Default, Serialize)]
struct InflightStats {
    min: i64,
    avg: f64,
    p95: i64,
    max: i64,
    target: i64,
    maintained_pct: f64,
}

#[derive(Debug, Serialize)]
struct Report {
    label: String,
    protocol: String,
    stream: bool,
    concurrency: usize,
    ramp_sec: f64,
    duration_sec: f64,
    payload_bytes: usize,
    payload_tokens: usize,
    requests: i64,
    ok: i64,
    errors: i64,
    status_counts: BTreeMap<u16, i64>,
    #[serde(skip_serializing_if = "String::is_empty")]
    sample_error: String,
    rps: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    events_per_sec: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    aggregate_output_tps: Option<f64>,
    inbound_mb_per_sec: f64,
    read_mb_per_sec: f64,
    latency_ms: Percentiles,
    #[serde(skip_serializing_if = "Option::is_none")]
    ttft_ms: Option<Percentiles>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tpot_ms: Option<Percentiles>,
    #[serde(skip_serializing_if = "Option::is_none")]
    stream_tps: Option<Percentiles>,
    inflight: InflightStats,
}

#[derive(Default)]
struct WorkerResult {
    latencies_ns: Vec<i64>,
    ttfts_ns: Vec<i64>,
    tpots_ns: Vec<i64>,
    stream_tps: Vec<f64>,
    statuses: BTreeMap<u16, i64>,
    errors: i64,
    sample_error: String,
    events: i64,
    bytes_read: i64,
}

struct TargetEndpoint {
    addr: String,
    host_header: String,
    base_path: String,
}

fn parse_target(target: &str) -> Result<TargetEndpoint, String> {
    let trimmed = target.trim().trim_end_matches('/');
    let rest = if let Some(r) = trimmed.strip_prefix("http://") {
        r
    } else if trimmed.starts_with("https://") {
        return Err("https:// targets are not supported by loadgen (use http://)".to_string());
    } else {
        trimmed
    };
    let (host_port, base_path) = match rest.find('/') {
        Some(idx) => (&rest[..idx], rest[idx..].trim_end_matches('/').to_string()),
        None => (rest, String::new()),
    };
    let addr = if host_port.contains(':') {
        host_port.to_string()
    } else {
        format!("{host_port}:80")
    };
    Ok(TargetEndpoint {
        addr,
        host_header: host_port.to_string(),
        base_path,
    })
}

/// Builds the exact same JSON body & full pre-encoded HTTP/1.1 request wire bytes as Go `loadgen`.
fn build_raw_request(cfg: &Config, ep: &TargetEndpoint) -> (String, usize, Arc<[u8]>) {
    let target_bytes = cfg.payload_tokens * 4;
    let padding = "The gateway routes multimodal agent requests with deterministic micro-USD billing and non-blocking I/O. ".repeat(10);
    let asst_content = format!("Acknowledged context chunk: {padding}");
    let user_content = format!("Next file excerpt for analysis: {padding}");

    let (path, auth_headers, body) = if cfg.protocol == "anthropic" {
        let mut msgs = vec![serde_json::json!({
            "role": "user",
            "content": "Summarize the benchmark payload."
        })];
        let mut body_val = serde_json::json!({
            "model": cfg.model,
            "stream": cfg.stream,
            "max_tokens": cfg.max_tokens,
            "system": "You are a concise coding agent.",
            "messages": msgs,
        });
        let mut raw = serde_json::to_vec(&body_val).unwrap();
        while raw.len() < target_bytes {
            msgs.push(serde_json::json!({"role": "assistant", "content": asst_content}));
            msgs.push(serde_json::json!({"role": "user", "content": user_content}));
            body_val["messages"] = serde_json::Value::Array(msgs.clone());
            raw = serde_json::to_vec(&body_val).unwrap();
        }
        (
            format!("{}/v1/messages", ep.base_path),
            format!("x-api-key: {}\r\nanthropic-version: 2023-06-01\r\n", cfg.api_key),
            raw,
        )
    } else {
        let mut msgs = vec![
            serde_json::json!({"role": "system", "content": "You are a concise coding agent."}),
            serde_json::json!({"role": "user", "content": "Summarize the benchmark payload."}),
        ];
        let mut body_val = serde_json::json!({
            "model": cfg.model,
            "stream": cfg.stream,
            "max_tokens": cfg.max_tokens,
            "messages": msgs,
        });
        let mut raw = serde_json::to_vec(&body_val).unwrap();
        while raw.len() < target_bytes {
            msgs.push(serde_json::json!({"role": "assistant", "content": asst_content}));
            msgs.push(serde_json::json!({"role": "user", "content": user_content}));
            body_val["messages"] = serde_json::Value::Array(msgs.clone());
            raw = serde_json::to_vec(&body_val).unwrap();
        }
        (
            format!("{}/v1/chat/completions", ep.base_path),
            format!("Authorization: Bearer {}\r\n", cfg.api_key),
            raw,
        )
    };

    let mut wire = Vec::with_capacity(body.len() + 512);
    use std::io::Write;
    let _ = write!(
        &mut wire,
        "POST {path} HTTP/1.1\r\nHost: {}\r\nContent-Type: application/json\r\n{auth_headers}Content-Length: {}\r\nConnection: keep-alive\r\n\r\n",
        ep.host_header,
        body.len()
    );
    wire.extend_from_slice(&body);
    let display_url = format!("{}{}", cfg.target.trim_end_matches('/'), path);
    (display_url, body.len(), Arc::from(wire))
}

struct ResponseHead {
    status: u16,
    chunked: bool,
    content_length: Option<usize>,
    keep_alive: bool,
    header_end: usize,
}

fn parse_response_head(buf: &[u8], header_end: usize) -> io::Result<ResponseHead> {
    let head = std::str::from_utf8(&buf[..header_end - 4])
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid utf8 response head"))?;
    let mut lines = head.split("\r\n");
    let status_line = lines
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "empty response head"))?;
    let mut parts = status_line.split_whitespace();
    let version = parts.next().unwrap_or("HTTP/1.1");
    let status: u16 = parts
        .next()
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, format!("bad status: {status_line}")))?;

    let mut chunked = false;
    let mut content_length = None;
    let mut keep_alive = !version.eq_ignore_ascii_case("HTTP/1.0");
    for line in lines {
        if let Some((k, v)) = line.split_once(':') {
            let val = v.trim();
            if k.eq_ignore_ascii_case("transfer-encoding") {
                if val.to_ascii_lowercase().contains("chunked") {
                    chunked = true;
                }
            } else if k.eq_ignore_ascii_case("content-length") {
                if let Ok(n) = val.parse::<usize>() {
                    content_length = Some(n);
                }
            } else if k.eq_ignore_ascii_case("connection") {
                if val.eq_ignore_ascii_case("close") {
                    keep_alive = false;
                } else if val.eq_ignore_ascii_case("keep-alive") {
                    keep_alive = true;
                }
            }
        }
    }
    Ok(ResponseHead {
        status,
        chunked,
        content_length,
        keep_alive,
        header_end,
    })
}

/// Incremental SSE line scanner that operates directly on decoded body slices without allocating
/// per-line `String`s, matching Go `readSSE` semantics identically.
struct SseScanner<'a> {
    marker: Finder<'a>,
    start: Instant,
    ttft_ns: i64,
    frames: i64,
    read_bytes: i64,
    line_buf: Vec<u8>,
}

impl<'a> SseScanner<'a> {
    fn new(protocol: &str, start: Instant) -> Self {
        let needle: &'static [u8] = if protocol == "anthropic" {
            b"\"text_delta\""
        } else {
            b"\"content\":"
        };
        Self {
            marker: Finder::new(needle),
            start,
            ttft_ns: 0,
            frames: 0,
            read_bytes: 0,
            line_buf: Vec::with_capacity(512),
        }
    }

    #[inline]
    fn feed(&mut self, mut chunk: &[u8]) {
        self.read_bytes += chunk.len() as i64;
        while !chunk.is_empty() {
            if let Some(nl) = memchr::memchr(b'\n', chunk) {
                let piece = &chunk[..=nl];
                if self.line_buf.is_empty() {
                    self.process_line(piece);
                } else {
                    self.line_buf.extend_from_slice(piece);
                    let line = std::mem::take(&mut self.line_buf);
                    self.process_line(&line);
                    self.line_buf = line;
                    self.line_buf.clear();
                }
                chunk = &chunk[nl + 1..];
            } else {
                self.line_buf.extend_from_slice(chunk);
                break;
            }
        }
    }

    fn finish(&mut self) {
        if !self.line_buf.is_empty() {
            let line = std::mem::take(&mut self.line_buf);
            self.process_line(&line);
            self.line_buf = line;
            self.line_buf.clear();
        }
    }

    #[inline]
    fn process_line(&mut self, line: &[u8]) {
        if line.starts_with(b"data:") {
            if self.ttft_ns == 0 {
                self.ttft_ns = self.start.elapsed().as_nanos() as i64;
            }
            let payload = trim_ascii_whitespace(&line[5..]);
            if !payload.is_empty() && payload != b"[DONE]" && self.marker.find(payload).is_some() {
                self.frames += 1;
            }
        }
    }
}

#[inline]
fn trim_ascii_whitespace(mut s: &[u8]) -> &[u8] {
    while let Some((&b, rest)) = s.split_first() {
        if b.is_ascii_whitespace() {
            s = rest;
        } else {
            break;
        }
    }
    while let Some((&b, rest)) = s.split_last() {
        if b.is_ascii_whitespace() {
            s = rest;
        } else {
            break;
        }
    }
    s
}

struct BodyStats {
    status: u16,
    ttft_ns: i64,
    frames: i64,
    read_bytes: i64,
    keep_alive: bool,
}

async fn send_and_read_once(
    stream: &mut TcpStream,
    buf: &mut BytesMut,
    req_bytes: &[u8],
    cfg: &Config,
    start: Instant,
) -> io::Result<BodyStats> {
    stream.write_all(req_bytes).await?;

    // Read HTTP response headers.
    let header_end = loop {
        if let Some(pos) = memchr::memmem::find(buf, b"\r\n\r\n") {
            break pos + 4;
        }
        if buf.len() > 64 * 1024 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "response header too large",
            ));
        }
        if stream.read_buf(buf).await? == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "EOF reading response headers",
            ));
        }
    };

    let head = parse_response_head(buf, header_end)?;
    let _ = buf.split_to(head.header_end);

    let parse_sse = head.status / 100 == 2 && cfg.stream;
    let mut sse = if parse_sse {
        Some(SseScanner::new(&cfg.protocol, start))
    } else {
        None
    };
    let mut plain_ttft_ns: i64 = 0;
    let mut plain_read_bytes: i64 = 0;

    let mut feed_body = |slice: &[u8]| {
        if slice.is_empty() {
            return;
        }
        if let Some(ref mut s) = sse {
            s.feed(slice);
        } else {
            if plain_ttft_ns == 0 {
                plain_ttft_ns = start.elapsed().as_nanos() as i64;
            }
            plain_read_bytes += slice.len() as i64;
        }
    };

    if head.chunked {
        loop {
            let line_end = loop {
                if let Some(pos) = memchr::memmem::find(buf, b"\r\n") {
                    break pos;
                }
                if stream.read_buf(buf).await? == 0 {
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        "EOF reading chunk size",
                    ));
                }
            };
            let size_str = std::str::from_utf8(&buf[..line_end])
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad chunk size"))?;
            let hex = size_str.split(';').next().unwrap_or("").trim();
            let mut rem = usize::from_str_radix(hex, 16)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad chunk hex"))?;
            let _ = buf.split_to(line_end + 2);

            if rem == 0 {
                // Consume final \r\n (or trailing headers).
                loop {
                    if buf.starts_with(b"\r\n") {
                        let _ = buf.split_to(2);
                        break;
                    }
                    if let Some(pos) = memchr::memmem::find(buf, b"\r\n\r\n") {
                        let _ = buf.split_to(pos + 4);
                        break;
                    }
                    if stream.read_buf(buf).await? == 0 {
                        break;
                    }
                }
                break;
            }

            while rem > 0 {
                if buf.is_empty() && stream.read_buf(buf).await? == 0 {
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        "EOF reading chunk body",
                    ));
                }
                let take = rem.min(buf.len());
                feed_body(&buf[..take]);
                let _ = buf.split_to(take);
                rem -= take;
            }

            while buf.len() < 2 {
                if stream.read_buf(buf).await? == 0 {
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        "EOF reading chunk CRLF",
                    ));
                }
            }
            let _ = buf.split_to(2);
        }
    } else if let Some(mut rem) = head.content_length {
        while rem > 0 {
            if buf.is_empty() && stream.read_buf(buf).await? == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "EOF reading fixed body",
                ));
            }
            let take = rem.min(buf.len());
            feed_body(&buf[..take]);
            let _ = buf.split_to(take);
            rem -= take;
        }
    } else {
        // Read until EOF (non-keep-alive response without Content-Length).
        loop {
            if !buf.is_empty() {
                feed_body(buf);
                buf.clear();
            }
            if stream.read_buf(buf).await? == 0 {
                break;
            }
        }
        if let Some(ref mut s) = sse {
            s.finish();
            return Ok(BodyStats {
                status: head.status,
                ttft_ns: s.ttft_ns,
                frames: s.frames,
                read_bytes: s.read_bytes,
                keep_alive: false,
            });
        }
        return Ok(BodyStats {
            status: head.status,
            ttft_ns: plain_ttft_ns,
            frames: 0,
            read_bytes: plain_read_bytes,
            keep_alive: false,
        });
    }

    if let Some(ref mut s) = sse {
        s.finish();
        Ok(BodyStats {
            status: head.status,
            ttft_ns: s.ttft_ns,
            frames: s.frames,
            read_bytes: s.read_bytes,
            keep_alive: head.keep_alive,
        })
    } else {
        Ok(BodyStats {
            status: head.status,
            ttft_ns: plain_ttft_ns,
            frames: 0,
            read_bytes: plain_read_bytes,
            keep_alive: head.keep_alive,
        })
    }
}

async fn connect_fresh(addr: &str) -> io::Result<TcpStream> {
    let stream = tokio::time::timeout(Duration::from_secs(10), TcpStream::connect(addr))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "connect timeout"))??;
    let _ = stream.set_nodelay(true);
    Ok(stream)
}

async fn run_worker(
    addr: Arc<str>,
    req_bytes: Arc<[u8]>,
    cfg: Arc<Config>,
    start_at: Instant,
    measure_start: Instant,
    run_end: Instant,
    inflight: Arc<AtomicI64>,
) -> WorkerResult {
    let mut res = WorkerResult::default();
    let now = Instant::now();
    if start_at > now {
        tokio::time::sleep_until(start_at).await;
    }

    let mut conn: Option<TcpStream> = None;
    let mut buf = BytesMut::with_capacity(8192);

    loop {
        let now = Instant::now();
        if now >= run_end {
            return res;
        }
        let recording = now >= measure_start;
        inflight.fetch_add(1, Ordering::Relaxed);
        let start = Instant::now();

        let attempt_res: io::Result<BodyStats> =
            tokio::time::timeout(cfg.timeout, async {
                let mut reused = conn.is_some();
                loop {
                    if conn.is_none() {
                        buf.clear();
                        conn = Some(connect_fresh(&addr).await?);
                        reused = false;
                    }
                    let stream = conn.as_mut().unwrap();
                    match send_and_read_once(stream, &mut buf, &req_bytes, &cfg, start).await {
                        Ok(st) => {
                            if !st.keep_alive {
                                conn = None;
                                buf.clear();
                            }
                            return Ok(st);
                        }
                        Err(e) => {
                            conn = None;
                            buf.clear();
                            // Mirror Go http.Transport: retry once if an idle keep-alive socket was closed.
                            if reused {
                                reused = false;
                                continue;
                            }
                            return Err(e);
                        }
                    }
                }
            })
            .await
            .unwrap_or_else(|_| Err(io::Error::new(io::ErrorKind::TimedOut, "request timeout")));

        let elapsed_ns = start.elapsed().as_nanos() as i64;
        inflight.fetch_sub(1, Ordering::Relaxed);

        match attempt_res {
            Err(e) => {
                if recording {
                    res.errors += 1;
                    if res.sample_error.is_empty() {
                        res.sample_error = e.to_string();
                    }
                }
            }
            Ok(st) => {
                if !recording {
                    continue;
                }
                *res.statuses.entry(st.status).or_insert(0) += 1;
                res.latencies_ns.push(elapsed_ns);
                res.events += st.frames;
                res.bytes_read += st.read_bytes;
                if st.status / 100 != 2 {
                    res.errors += 1;
                    if res.sample_error.is_empty() {
                        res.sample_error = format!("HTTP {}", st.status);
                    }
                    continue;
                }
                if cfg.stream && st.frames > 0 {
                    res.ttfts_ns.push(st.ttft_ns);
                    let decode_ns = elapsed_ns - st.ttft_ns;
                    if st.frames > 1 && decode_ns > 0 {
                        res.tpots_ns.push(decode_ns / (st.frames - 1));
                        let decode_secs = (decode_ns as f64) / 1e9;
                        res.stream_tps.push(
                            ((st.frames - 1) as f64) * (cfg.tokens_per_frame as f64) / decode_secs,
                        );
                    }
                }
            }
        }
    }
}

fn round2(v: f64) -> f64 {
    (v * 100.0).round() / 100.0
}

fn compute_ns_percentiles(mut ns: Vec<i64>) -> Percentiles {
    if ns.is_empty() {
        return Percentiles::default();
    }
    ns.sort_unstable();
    let sum: i128 = ns.iter().map(|&v| v as i128).sum();
    let n = ns.len();
    let at = |q: f64| -> f64 {
        let idx = ((q * (n as f64)).ceil() as isize - 1).clamp(0, (n - 1) as isize) as usize;
        round2((ns[idx] as f64) / 1e6)
    };
    Percentiles {
        min: round2((ns[0] as f64) / 1e6),
        mean: round2((sum as f64) / (n as f64) / 1e6),
        p50: at(0.50),
        p90: at(0.90),
        p95: at(0.95),
        p99: at(0.99),
        max: round2((ns[n - 1] as f64) / 1e6),
    }
}

fn compute_f64_percentiles(mut vals: Vec<f64>) -> Percentiles {
    if vals.is_empty() {
        return Percentiles::default();
    }
    vals.sort_by(|a, b| a.total_cmp(b));
    let sum: f64 = vals.iter().sum();
    let n = vals.len();
    let at = |q: f64| -> f64 {
        let idx = ((q * (n as f64)).ceil() as isize - 1).clamp(0, (n - 1) as isize) as usize;
        round2(vals[idx])
    };
    Percentiles {
        min: round2(vals[0]),
        mean: round2(sum / (n as f64)),
        p50: at(0.50),
        p90: at(0.90),
        p95: at(0.95),
        p99: at(0.99),
        max: round2(vals[n - 1]),
    }
}

fn compute_inflight(mut samples: Vec<i64>, target: i64) -> InflightStats {
    if samples.is_empty() {
        return InflightStats {
            target,
            ..Default::default()
        };
    }
    samples.sort_unstable();
    let sum: i128 = samples.iter().map(|&v| v as i128).sum();
    let n = samples.len();
    let p95_idx = ((0.95 * (n as f64)).ceil() as isize - 1).clamp(0, (n - 1) as isize) as usize;
    let threshold = target * 95 / 100;
    let maintained = samples.iter().filter(|&&v| v >= threshold).count();
    InflightStats {
        min: samples[0],
        avg: round2((sum as f64) / (n as f64)),
        p95: samples[p95_idx],
        max: samples[n - 1],
        target,
        maintained_pct: round2(100.0 * (maintained as f64) / (n as f64)),
    }
}

fn aggregate(
    cfg: &Config,
    payload_bytes: usize,
    sec: f64,
    results: Vec<WorkerResult>,
    inflight_samples: Vec<i64>,
) -> Report {
    let mut statuses = BTreeMap::new();
    let mut lat = Vec::new();
    let mut ttft = Vec::new();
    let mut tpot = Vec::new();
    let mut tps = Vec::new();
    let mut err_req: i64 = 0;
    let mut events: i64 = 0;
    let mut read_bytes: i64 = 0;
    let mut sample_err = String::new();

    for r in results {
        lat.extend(r.latencies_ns);
        ttft.extend(r.ttfts_ns);
        tpot.extend(r.tpots_ns);
        tps.extend(r.stream_tps);
        for (k, v) in r.statuses {
            *statuses.entry(k).or_insert(0) += v;
        }
        err_req += r.errors;
        events += r.events;
        read_bytes += r.bytes_read;
        if sample_err.is_empty() && !r.sample_error.is_empty() {
            sample_err = r.sample_error;
        }
    }

    let total_req = lat.len() as i64;
    let ok_req = (total_req - err_req).max(0);
    let rps = round2((ok_req as f64) / sec);
    let inbound_mb = round2(((ok_req as f64) * (payload_bytes as f64)) / (1024.0 * 1024.0) / sec);
    let read_mb = round2((read_bytes as f64) / (1024.0 * 1024.0) / sec);

    let (events_per_sec, aggregate_output_tps, ttft_ms) = if cfg.stream && !ttft.is_empty() {
        let eps = round2((events as f64) / sec);
        let agg = round2(eps * (cfg.tokens_per_frame as f64));
        (Some(eps), Some(agg), Some(compute_ns_percentiles(ttft)))
    } else {
        (None, None, None)
    };
    let tpot_ms = if !tpot.is_empty() {
        Some(compute_ns_percentiles(tpot))
    } else {
        None
    };
    let stream_tps = if !tps.is_empty() {
        Some(compute_f64_percentiles(tps))
    } else {
        None
    };

    Report {
        label: cfg.label.clone(),
        protocol: cfg.protocol.clone(),
        stream: cfg.stream,
        concurrency: cfg.concurrency,
        ramp_sec: cfg.ramp.as_secs_f64(),
        duration_sec: round2(sec),
        payload_bytes,
        payload_tokens: cfg.payload_tokens,
        requests: total_req,
        ok: ok_req,
        errors: err_req,
        status_counts: statuses,
        sample_error: sample_err,
        rps,
        events_per_sec,
        aggregate_output_tps,
        inbound_mb_per_sec: inbound_mb,
        read_mb_per_sec: read_mb,
        latency_ms: compute_ns_percentiles(lat),
        ttft_ms,
        tpot_ms,
        stream_tps,
        inflight: compute_inflight(inflight_samples, cfg.concurrency as i64),
    }
}

fn print_report(r: &Report) {
    println!("---- Result ----");
    println!(
        "  Requests: ok={} err={} statuses={:?} ({:.2}s)",
        r.ok, r.errors, r.status_counts, r.duration_sec
    );
    print!(
        "  Throughput: RPS={:.2} Inbound={:.2} MB/s Read={:.2} MB/s",
        r.rps, r.inbound_mb_per_sec, r.read_mb_per_sec
    );
    if r.stream {
        println!(
            "  SSE={:.0} frames/s  OutTPS={:.0}",
            r.events_per_sec.unwrap_or(0.0),
            r.aggregate_output_tps.unwrap_or(0.0)
        );
    } else {
        println!();
    }
    println!(
        "  Latency (ms): min={:.1} mean={:.1} p50={:.1} p90={:.1} p95={:.1} p99={:.1} max={:.1}",
        r.latency_ms.min,
        r.latency_ms.mean,
        r.latency_ms.p50,
        r.latency_ms.p90,
        r.latency_ms.p95,
        r.latency_ms.p99,
        r.latency_ms.max
    );
    if let Some(ref t) = r.ttft_ms {
        println!(
            "  TTFT (ms):    min={:.1} mean={:.1} p50={:.1} p90={:.1} p95={:.1} p99={:.1} max={:.1}",
            t.min, t.mean, t.p50, t.p90, t.p95, t.p99, t.max
        );
    }
    if let Some(ref t) = r.tpot_ms {
        println!(
            "  TPOT (ms):    p50={:.2} p95={:.2} p99={:.2}",
            t.p50, t.p95, t.p99
        );
    }
    if let Some(ref s) = r.stream_tps {
        println!(
            "  StreamTPS:    min={:.1} p50={:.1} p95={:.1} p99={:.1} tok/s",
            s.min, s.p50, s.p95, s.p99
        );
    }
    println!(
        "  In-flight:    min={} avg={:.1} p95={} max={} target={} maintained={:.1}%",
        r.inflight.min,
        r.inflight.avg,
        r.inflight.p95,
        r.inflight.max,
        r.inflight.target,
        r.inflight.maintained_pct
    );
    if !r.sample_error.is_empty() {
        println!("  Sample error: {}", r.sample_error);
    }
}

fn main() {
    let flags = match Flags::parse(std::env::args()) {
        Ok(f) => f,
        Err(msg) if msg == "help" => {
            eprintln!("Usage: loadgen -target URL -protocol chat|anthropic -model MODEL -api-key KEY -stream=true -payload-tokens 100000 -max-tokens 2000 -concurrency 300 -ramp 20s -duration 60s -timeout 300s -tokens-per-frame 5 -label LABEL -json-out PATH");
            std::process::exit(0);
        }
        Err(e) => {
            eprintln!("ERROR: {e}");
            std::process::exit(2);
        }
    };

    let target = flags.get_str("target", "http://127.0.0.1:8080");
    let protocol = flags.get_str("protocol", "chat");
    let model = flags.get_str("model", "bench-openai-chat");
    let api_key = flags.get_str(
        "api-key",
        "sk-bench0010000000000000000000000000000000000000001",
    );
    let stream = flags.get_bool("stream", true).unwrap();
    let payload_tokens = flags.get_usize("payload-tokens", 100_000).unwrap();
    let max_tokens = flags.get_usize("max-tokens", 2_000).unwrap();
    let concurrency = flags.get_usize("concurrency", 300).unwrap();
    let ramp = flags.get_duration("ramp", Duration::from_secs(20)).unwrap();
    let duration = flags
        .get_duration("duration", Duration::from_secs(60))
        .unwrap();
    let timeout = flags
        .get_duration("timeout", Duration::from_secs(300))
        .unwrap();
    let tokens_per_frame = flags.get_usize("tokens-per-frame", 5).unwrap();
    let json_out = flags.get_str("json-out", "");
    let mut label = flags.get_str("label", "");
    if label.is_empty() {
        let mode = if stream { "stream" } else { "nonstream" };
        label = format!("{protocol}_{mode}_c{concurrency}");
    }

    let cfg = Arc::new(Config {
        target,
        protocol,
        model,
        api_key,
        stream,
        payload_tokens,
        max_tokens,
        concurrency,
        ramp,
        duration,
        timeout,
        tokens_per_frame,
        json_out,
        label,
    });

    let ep = parse_target(&cfg.target).unwrap_or_else(|e| {
        eprintln!("ERROR: {e}");
        std::process::exit(2);
    });
    let (display_url, payload_bytes, req_bytes) = build_raw_request(&cfg, &ep);
    let addr: Arc<str> = Arc::from(ep.addr);

    println!(
        "==> {}: {} {} concurrency={} ramp={}s duration={}s payload={}B (~{} tokens) stream={}",
        cfg.label,
        cfg.protocol,
        display_url,
        cfg.concurrency,
        cfg.ramp.as_secs_f64(),
        cfg.duration.as_secs_f64(),
        payload_bytes,
        cfg.payload_tokens,
        cfg.stream
    );

    let workers = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1);
    let rt = if workers <= 1 {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("build current_thread runtime")
    } else {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(workers)
            .enable_all()
            .build()
            .expect("build multi_thread runtime")
    };

    let cfg_run = Arc::clone(&cfg);
    let (results, inflight_samples) = rt.block_on(async move {
        let inflight = Arc::new(AtomicI64::new(0));
        let run_start = Instant::now();
        let measure_start = run_start + cfg_run.ramp;
        let run_end = measure_start + cfg_run.duration;

        let (stop_tx, mut stop_rx) = tokio::sync::oneshot::channel::<()>();
        let inf_sampler = Arc::clone(&inflight);
        let sampler_handle = tokio::spawn(async move {
            let mut samples = Vec::new();
            let mut ticker = tokio::time::interval(Duration::from_millis(100));
            loop {
                tokio::select! {
                    biased;
                    _ = &mut stop_rx => break,
                    now = ticker.tick() => {
                        if now >= measure_start && now <= run_end {
                            samples.push(inf_sampler.load(Ordering::Relaxed));
                        }
                    }
                }
            }
            samples
        });

        let mut handles = Vec::with_capacity(cfg_run.concurrency);
        let ramp_nanos = cfg_run.ramp.as_nanos() as f64;
        for i in 0..cfg_run.concurrency {
            let start_at = if cfg_run.concurrency > 1 {
                let offset = (ramp_nanos * (i as f64) / (cfg_run.concurrency as f64)) as u64;
                run_start + Duration::from_nanos(offset)
            } else {
                run_start
            };
            handles.push(tokio::spawn(run_worker(
                Arc::clone(&addr),
                Arc::clone(&req_bytes),
                Arc::clone(&cfg_run),
                start_at,
                measure_start,
                run_end,
                Arc::clone(&inflight),
            )));
        }

        let mut results = Vec::with_capacity(handles.len());
        for h in handles {
            if let Ok(r) = h.await {
                results.push(r);
            }
        }
        let _ = stop_tx.send(());
        let samples = sampler_handle.await.unwrap_or_default();
        (results, samples)
    });

    let rep = aggregate(
        &cfg,
        payload_bytes,
        cfg.duration.as_secs_f64(),
        results,
        inflight_samples,
    );
    if rep.requests == 0 {
        println!(
            "WARNING: no request was started inside the measured window; raise -duration (the window must be longer than one stream, ~10s at the defaults) or lower -ramp"
        );
    }
    print_report(&rep);
    if !cfg.json_out.is_empty() {
        match File::create(&cfg.json_out)
            .and_then(|f| serde_json::to_writer_pretty(f, &rep).map_err(io::Error::other))
        {
            Ok(()) => {}
            Err(e) => eprintln!("write {}: {e}", cfg.json_out),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sse_scanner_counts_anthropic_and_openai_frames() {
        let start = Instant::now();
        let mut s_anth = SseScanner::new("anthropic", start);
        s_anth.feed(b"event: message_start\ndata: {\"type\":\"message_start\"}\n\n");
        s_anth.feed(b"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hi \"}}\n\n");
        s_anth.feed(b"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"there\"}}\n\n");
        s_anth.feed(b"data: [DONE]\n\n");
        s_anth.finish();
        assert_eq!(s_anth.frames, 2);

        let mut s_oai = SseScanner::new("chat", start);
        // Split across arbitrary chunk boundaries
        s_oai.feed(b"data: {\"choices\":[{\"delta\":{\"con");
        s_oai.feed(b"tent\":\"hello\"}}]}\n\ndata: [DONE]\n\n");
        s_oai.finish();
        assert_eq!(s_oai.frames, 1);
    }

    #[test]
    fn builds_payload_matching_target_tokens() {
        let cfg = Config {
            target: "http://127.0.0.1:18081".into(),
            protocol: "anthropic".into(),
            model: "bench-openai-chat".into(),
            api_key: "sk-test".into(),
            stream: true,
            payload_tokens: 1000,
            max_tokens: 200,
            concurrency: 1,
            ramp: Duration::from_secs(1),
            duration: Duration::from_secs(2),
            timeout: Duration::from_secs(10),
            tokens_per_frame: 5,
            json_out: String::new(),
            label: "test".into(),
        };
        let ep = parse_target(&cfg.target).unwrap();
        let (url, len, wire) = build_raw_request(&cfg, &ep);
        assert_eq!(url, "http://127.0.0.1:18081/v1/messages");
        assert!(len >= 4000);
        let head = std::str::from_utf8(&wire[..200]).unwrap();
        assert!(head.starts_with("POST /v1/messages HTTP/1.1\r\n"));
        assert!(head.contains("x-api-key: sk-test\r\n"));
    }
}
