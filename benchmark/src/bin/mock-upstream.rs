//! High-throughput, zero-GC OpenAI Chat Completions mock upstream for HyperLLM benchmarks.
//!
//! Drop-in replacement for the legacy Go `mock-upstream`:
//! - Same CLI flags (`-listen`, `-ttft-ms`, `-token-rate`, `-tokens-per-frame`, `-output-tokens`,
//!   `-input-tokens`, `-model`)
//! - Same HTTP endpoints (`GET /healthz`, `GET /stats`, `POST|GET /_mock/config`,
//!   `POST /v1/chat/completions`)
//! - Pre-encodes complete HTTP/1.1 Chunked frames once per config change (zero per-frame framing
//!   or allocation)
//! - Drains large 100K-token (~400 KB) request bodies into a per-connection reusable buffer using
//!   SIMD `memchr::memmem` without `String` conversion or Unicode lowercasing
//! - Defers the frame interval ticker until after TTFT sleep completes, and flushes `bytes_out`
//!   once per stream to eliminate cross-core atomic contention.

use bytes::{Bytes, BytesMut};
use hyperllm_bench::Flags;
use memchr::memmem::Finder;
use serde_json::json;
use std::io;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, RwLock};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::time::{Instant, MissedTickBehavior};

const READ_BUF_CAP: usize = 64 * 1024;
const STREAM_OVERLAP: usize = 32;
const STREAM_HEADERS: &[u8] = concat!(
    "HTTP/1.1 200 OK\r\n",
    "Content-Type: text/event-stream\r\n",
    "Cache-Control: no-cache\r\n",
    "Connection: keep-alive\r\n",
    "Transfer-Encoding: chunked\r\n\r\n",
)
.as_bytes();
const HEALTHZ_RESP: &[u8] = concat!(
    "HTTP/1.1 200 OK\r\n",
    "Content-Type: text/plain\r\n",
    "Content-Length: 2\r\n",
    "Connection: keep-alive\r\n\r\n",
    "ok",
)
.as_bytes();
const NOT_FOUND_RESP: &[u8] = concat!(
    "HTTP/1.1 404 Not Found\r\n",
    "Content-Length: 9\r\n",
    "Connection: keep-alive\r\n\r\n",
    "not found",
)
.as_bytes();
const CONTINUE_100: &[u8] = b"HTTP/1.1 100 Continue\r\n\r\n";

struct RuntimeConfig {
    ttft_ms: AtomicI64,
    token_rate: AtomicI64,
    tokens_per_frame: AtomicI64,
    output_tokens: AtomicI64,
    input_tokens: AtomicI64,
    model: RwLock<String>,
}

struct ServerStats {
    streams_active: AtomicI64,
    streams_total: AtomicI64,
    requests_total: AtomicI64,
    bytes_out: AtomicI64,
    aborted: AtomicI64,
}

impl Default for ServerStats {
    fn default() -> Self {
        Self {
            streams_active: AtomicI64::new(0),
            streams_total: AtomicI64::new(0),
            requests_total: AtomicI64::new(0),
            bytes_out: AtomicI64::new(0),
            aborted: AtomicI64::new(0),
        }
    }
}

struct FrameSet {
    /// Pre-framed HTTP/1.1 chunk (`<hex>\r\n<sse>\r\n`) for frame 0 (includes role=assistant).
    chunk_first: Bytes,
    first_raw_len: i64,
    /// Pre-framed HTTP/1.1 chunk reused for frames 1..total_frames-1.
    chunk_delta: Bytes,
    delta_raw_len: i64,
    /// Pre-framed HTTP/1.1 chunk for finish_reason + usage + [DONE] plus terminating `0\r\n\r\n`.
    chunk_end: Bytes,
    end_raw_len: i64,
    /// Complete pre-built HTTP/1.1 200 OK JSON response for non-streaming requests.
    non_stream_resp: Bytes,
    non_stream_raw_len: i64,
    total_frames: i64,
    frame_interval: Duration,
}

struct AppState {
    cfg: RuntimeConfig,
    stats: ServerStats,
    frame_set: RwLock<Arc<FrameSet>>,
    finder_tight: Finder<'static>,
    finder_spaced: Finder<'static>,
}

impl AppState {
    fn new(
        ttft_ms: i64,
        token_rate: i64,
        tokens_per_frame: i64,
        output_tokens: i64,
        input_tokens: i64,
        model: String,
    ) -> Self {
        let fs = Arc::new(build_frame_set(
            &model,
            input_tokens,
            output_tokens,
            tokens_per_frame,
            token_rate,
        ));
        Self {
            cfg: RuntimeConfig {
                ttft_ms: AtomicI64::new(ttft_ms),
                token_rate: AtomicI64::new(token_rate),
                tokens_per_frame: AtomicI64::new(tokens_per_frame),
                output_tokens: AtomicI64::new(output_tokens),
                input_tokens: AtomicI64::new(input_tokens),
                model: RwLock::new(model),
            },
            stats: ServerStats::default(),
            frame_set: RwLock::new(fs),
            finder_tight: Finder::new(b"\"stream\":true"),
            finder_spaced: Finder::new(b"\"stream\": true"),
        }
    }

    fn refresh(&self) -> Arc<FrameSet> {
        let model = self
            .cfg
            .model
            .read()
            .map(|g| {
                if g.is_empty() {
                    "bench-openai-chat".to_string()
                } else {
                    g.clone()
                }
            })
            .unwrap_or_else(|_| "bench-openai-chat".to_string());
        let fs = Arc::new(build_frame_set(
            &model,
            self.cfg.input_tokens.load(Ordering::Relaxed),
            self.cfg.output_tokens.load(Ordering::Relaxed),
            self.cfg.tokens_per_frame.load(Ordering::Relaxed),
            self.cfg.token_rate.load(Ordering::Relaxed),
        ));
        if let Ok(mut guard) = self.frame_set.write() {
            *guard = Arc::clone(&fs);
        }
        fs
    }

    fn current_frame_set(&self) -> Arc<FrameSet> {
        self.frame_set
            .read()
            .expect("frame_set lock poisoned")
            .clone()
    }
}

fn encode_chunk(payload: &[u8], terminate: bool) -> Bytes {
    let mut out = Vec::with_capacity(payload.len() + 24);
    use std::io::Write;
    let _ = write!(&mut out, "{:x}\r\n", payload.len());
    out.extend_from_slice(payload);
    if terminate {
        out.extend_from_slice(b"\r\n0\r\n\r\n");
    } else {
        out.extend_from_slice(b"\r\n");
    }
    Bytes::from(out)
}

fn build_frame_set(
    model: &str,
    input_tokens: i64,
    mut output_tokens: i64,
    mut tokens_per_frame: i64,
    mut token_rate: i64,
) -> FrameSet {
    if tokens_per_frame < 1 {
        tokens_per_frame = 1;
    }
    if token_rate < 1 {
        token_rate = 1;
    }
    let mut frames = output_tokens / tokens_per_frame;
    if frames < 1 {
        frames = 1;
    }
    if output_tokens < tokens_per_frame {
        output_tokens = tokens_per_frame;
    }
    let frame_text = "token ".repeat(tokens_per_frame as usize);
    let model_json = serde_json::to_string(model).unwrap_or_else(|_| "\"bench-openai-chat\"".into());
    let frame_text_json = serde_json::to_string(&frame_text).unwrap();

    let usage = format!(
        "{{\"prompt_tokens\":{input_tokens},\"completion_tokens\":{output_tokens},\"total_tokens\":{},\"prompt_tokens_details\":{{\"cached_tokens\":0}},\"completion_tokens_details\":{{\"reasoning_tokens\":0}}}}",
        input_tokens + output_tokens
    );

    let first = format!(
        "data: {{\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,\"model\":{model_json},\"choices\":[{{\"index\":0,\"delta\":{{\"role\":\"assistant\",\"content\":{frame_text_json}}},\"finish_reason\":null}}]}}\n\n"
    );
    let delta = format!(
        "data: {{\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,\"model\":{model_json},\"choices\":[{{\"index\":0,\"delta\":{{\"content\":{frame_text_json}}},\"finish_reason\":null}}]}}\n\n"
    );
    let end = format!(
        "data: {{\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,\"model\":{model_json},\"choices\":[{{\"index\":0,\"delta\":{{}},\"finish_reason\":\"stop\"}}]}}\n\ndata: {{\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,\"model\":{model_json},\"choices\":[],\"usage\":{usage}}}\n\ndata: [DONE]\n\n"
    );

    let non_stream_content = "token ".repeat(output_tokens as usize);
    let non_stream_content_json = serde_json::to_string(&non_stream_content).unwrap();
    let non_stream_body = format!(
        "{{\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion\",\"created\":1730000000,\"model\":{model_json},\"choices\":[{{\"index\":0,\"message\":{{\"role\":\"assistant\",\"content\":{non_stream_content_json}}},\"finish_reason\":\"stop\"}}],\"usage\":{usage}}}"
    );
    let non_stream_resp = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: keep-alive\r\n\r\n{}",
        non_stream_body.len(),
        non_stream_body
    );

    let interval_nanos =
        ((tokens_per_frame as f64) / (token_rate as f64) * 1_000_000_000.0).round() as u64;

    FrameSet {
        chunk_first: encode_chunk(first.as_bytes(), false),
        first_raw_len: first.len() as i64,
        chunk_delta: encode_chunk(delta.as_bytes(), false),
        delta_raw_len: delta.len() as i64,
        chunk_end: encode_chunk(end.as_bytes(), true),
        end_raw_len: end.len() as i64,
        non_stream_raw_len: non_stream_body.len() as i64,
        non_stream_resp: Bytes::from(non_stream_resp),
        total_frames: frames,
        frame_interval: Duration::from_nanos(interval_nanos),
    }
}

struct ActiveStreamGuard<'a> {
    counter: &'a AtomicI64,
}

impl<'a> ActiveStreamGuard<'a> {
    fn new(counter: &'a AtomicI64) -> Self {
        counter.fetch_add(1, Ordering::Relaxed);
        Self { counter }
    }
}

impl Drop for ActiveStreamGuard<'_> {
    fn drop(&mut self) {
        self.counter.fetch_sub(1, Ordering::Relaxed);
    }
}

struct ParsedHeaders {
    path_and_query: String,
    content_length: Option<usize>,
    chunked: bool,
    keep_alive: bool,
    expect_continue: bool,
    header_end: usize,
}

fn find_header_end(buf: &[u8]) -> Option<usize> {
    memchr::memmem::find(buf, b"\r\n\r\n").map(|pos| pos + 4)
}

fn parse_headers(buf: &[u8], header_end: usize) -> io::Result<ParsedHeaders> {
    let head = std::str::from_utf8(&buf[..header_end - 4])
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "non-utf8 headers"))?;
    let mut lines = head.split("\r\n");
    let req_line = lines
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing request line"))?;
    let mut parts = req_line.split_whitespace();
    let _method = parts
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing method"))?;
    let target = parts
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing target"))?;
    let version = parts.next().unwrap_or("HTTP/1.1");

    let mut content_length = None;
    let mut chunked = false;
    let mut keep_alive = !version.eq_ignore_ascii_case("HTTP/1.0");
    let mut expect_continue = false;

    for line in lines {
        if let Some((name, val)) = line.split_once(':') {
            let v = val.trim();
            if name.eq_ignore_ascii_case("content-length") {
                if let Ok(n) = v.parse::<usize>() {
                    content_length = Some(n);
                }
            } else if name.eq_ignore_ascii_case("transfer-encoding") {
                if v.to_ascii_lowercase().contains("chunked") {
                    chunked = true;
                }
            } else if name.eq_ignore_ascii_case("connection") {
                if v.eq_ignore_ascii_case("close") {
                    keep_alive = false;
                } else if v.eq_ignore_ascii_case("keep-alive") {
                    keep_alive = true;
                }
            } else if name.eq_ignore_ascii_case("expect") && v.eq_ignore_ascii_case("100-continue")
            {
                expect_continue = true;
            }
        }
    }

    Ok(ParsedHeaders {
        path_and_query: target.to_string(),
        content_length,
        chunked,
        keep_alive,
        expect_continue,
        header_end,
    })
}

#[inline]
fn scan_stream_flag(state: &AppState, slice: &[u8]) -> bool {
    state.finder_tight.find(slice).is_some() || state.finder_spaced.find(slice).is_some()
}

/// Drains a `Content-Length` body in-place using a fixed reusable scratch buffer while checking
/// for `"stream":true` / `"stream": true` with a 32-byte overlap across buffer refills.
async fn drain_fixed_body(
    stream: &mut TcpStream,
    buf: &mut BytesMut,
    mut remaining: usize,
    state: &AppState,
    scratch: &mut [u8],
) -> io::Result<bool> {
    let mut is_stream = false;
    let mut overlap = [0u8; STREAM_OVERLAP];
    let mut overlap_len = 0usize;

    // 1. Inspect bytes already buffered right after the HTTP headers.
    if !buf.is_empty() && remaining > 0 {
        let take = remaining.min(buf.len());
        let slice = &buf[..take];
        if !is_stream && scan_stream_flag(state, slice) {
            is_stream = true;
        }
        if !is_stream {
            let keep = take.min(STREAM_OVERLAP);
            overlap[..keep].copy_from_slice(&slice[take - keep..take]);
            overlap_len = keep;
        }
        let _ = buf.split_to(take);
        remaining -= take;
    }

    // 2. Drain remaining body straight into reusable scratch (zero heap allocation).
    let scratch_len = scratch.len();
    while remaining > 0 {
        let max_read = remaining.min(scratch_len - overlap_len);
        if overlap_len > 0 && !is_stream {
            scratch[..overlap_len].copy_from_slice(&overlap[..overlap_len]);
        }
        let dst = if !is_stream {
            &mut scratch[overlap_len..overlap_len + max_read]
        } else {
            let take = remaining.min(scratch_len);
            &mut scratch[..take]
        };
        let n = stream.read(dst).await?;
        if n == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "eof draining fixed body",
            ));
        }
        if !is_stream {
            let total = overlap_len + n;
            if scan_stream_flag(state, &scratch[..total]) {
                is_stream = true;
                overlap_len = 0;
            } else {
                let keep = total.min(STREAM_OVERLAP);
                overlap[..keep].copy_from_slice(&scratch[total - keep..total]);
                overlap_len = keep;
            }
        }
        remaining -= n;
    }

    Ok(is_stream)
}

/// Drains an HTTP/1.1 chunked request body while scanning for `"stream":true`.
async fn drain_chunked_body(
    stream: &mut TcpStream,
    buf: &mut BytesMut,
    state: &AppState,
) -> io::Result<bool> {
    let mut is_stream = false;
    let mut overlap = [0u8; STREAM_OVERLAP];
    let mut overlap_len = 0usize;
    let mut scan_tmp = [0u8; STREAM_OVERLAP + 64];

    loop {
        // Read chunk-size line ending in \r\n.
        let line_end = loop {
            if let Some(pos) = memchr::memmem::find(buf, b"\r\n") {
                break pos;
            }
            if buf.len() > 8192 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "chunk header too long",
                ));
            }
            if stream.read_buf(buf).await? == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "eof in chunk size",
                ));
            }
        };
        let size_str = std::str::from_utf8(&buf[..line_end])
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad chunk size utf8"))?;
        let hex_part = size_str.split(';').next().unwrap_or("").trim();
        let mut chunk_rem = usize::from_str_radix(hex_part, 16)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad chunk size hex"))?;
        let _ = buf.split_to(line_end + 2);

        if chunk_rem == 0 {
            // Drain trailing headers until empty line (\r\n).
            loop {
                if buf.starts_with(b"\r\n") {
                    let _ = buf.split_to(2);
                    return Ok(is_stream);
                }
                if let Some(pos) = memchr::memmem::find(buf, b"\r\n\r\n") {
                    let _ = buf.split_to(pos + 4);
                    return Ok(is_stream);
                }
                if stream.read_buf(buf).await? == 0 {
                    return Ok(is_stream);
                }
            }
        }

        while chunk_rem > 0 {
            if buf.is_empty() && stream.read_buf(buf).await? == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "eof in chunk payload",
                ));
            }
            let take = chunk_rem.min(buf.len());
            if !is_stream {
                let slice = &buf[..take];
                if overlap_len > 0 {
                    let head = take.min(64);
                    scan_tmp[..overlap_len].copy_from_slice(&overlap[..overlap_len]);
                    scan_tmp[overlap_len..overlap_len + head].copy_from_slice(&slice[..head]);
                    if scan_stream_flag(state, &scan_tmp[..overlap_len + head]) {
                        is_stream = true;
                    }
                }
                if !is_stream && scan_stream_flag(state, slice) {
                    is_stream = true;
                }
                if !is_stream {
                    let keep = take.min(STREAM_OVERLAP);
                    overlap[..keep].copy_from_slice(&slice[take - keep..take]);
                    overlap_len = keep;
                }
            }
            let _ = buf.split_to(take);
            chunk_rem -= take;
        }

        // Consume trailing \r\n after chunk data.
        while buf.len() < 2 {
            if stream.read_buf(buf).await? == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "eof after chunk data",
                ));
            }
        }
        let _ = buf.split_to(2);
    }
}

/// Resolves only when the peer closes the TCP connection (EOF) or errors, without consuming any
/// buffered bytes.
async fn wait_peer_closed(stream: &TcpStream) {
    let mut peek_buf = [0u8; 1];
    loop {
        match stream.peek(&mut peek_buf).await {
            Ok(0) | Err(_) => return,
            Ok(_) => {
                // Unexpected pipelined data; sleep so we don't busy-spin.
                tokio::time::sleep(Duration::from_secs(60)).await;
            }
        }
    }
}

fn build_json_http_response(body: &str) -> Vec<u8> {
    format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: keep-alive\r\n\r\n{}",
        body.len(),
        body
    )
    .into_bytes()
}

fn handle_stats_response(state: &AppState) -> Vec<u8> {
    let model = state
        .cfg
        .model
        .read()
        .map(|g| g.clone())
        .unwrap_or_default();
    let payload = json!({
        "streams_active": state.stats.streams_active.load(Ordering::Relaxed),
        "streams_total": state.stats.streams_total.load(Ordering::Relaxed),
        "requests_total": state.stats.requests_total.load(Ordering::Relaxed),
        "bytes_out": state.stats.bytes_out.load(Ordering::Relaxed),
        "aborted_total": state.stats.aborted.load(Ordering::Relaxed),
        "config": {
            "ttft_ms": state.cfg.ttft_ms.load(Ordering::Relaxed),
            "token_rate": state.cfg.token_rate.load(Ordering::Relaxed),
            "tokens_per_frame": state.cfg.tokens_per_frame.load(Ordering::Relaxed),
            "output_tokens": state.cfg.output_tokens.load(Ordering::Relaxed),
            "input_tokens": state.cfg.input_tokens.load(Ordering::Relaxed),
            "model": model,
        }
    })
    .to_string();
    build_json_http_response(&payload)
}

fn handle_config_response(state: &AppState, query: &str) -> Vec<u8> {
    for pair in query.split('&') {
        if let Some((k, v)) = pair.split_once('=') {
            match k {
                "ttft_ms" => {
                    if let Ok(n) = v.parse::<i64>() {
                        if n > 0 {
                            state.cfg.ttft_ms.store(n, Ordering::Relaxed);
                        }
                    }
                }
                "token_rate" => {
                    if let Ok(n) = v.parse::<i64>() {
                        if n > 0 {
                            state.cfg.token_rate.store(n, Ordering::Relaxed);
                        }
                    }
                }
                "tokens_per_frame" => {
                    if let Ok(n) = v.parse::<i64>() {
                        if n > 0 {
                            state.cfg.tokens_per_frame.store(n, Ordering::Relaxed);
                        }
                    }
                }
                "output_tokens" => {
                    if let Ok(n) = v.parse::<i64>() {
                        if n > 0 {
                            state.cfg.output_tokens.store(n, Ordering::Relaxed);
                        }
                    }
                }
                "input_tokens" => {
                    if let Ok(n) = v.parse::<i64>() {
                        if n > 0 {
                            state.cfg.input_tokens.store(n, Ordering::Relaxed);
                        }
                    }
                }
                "model" if !v.is_empty() => {
                    if let Ok(mut g) = state.cfg.model.write() {
                        *g = url_decode_simple(v);
                    }
                }
                "reset_stats" if v == "1" => {
                    state.stats.streams_total.store(0, Ordering::Relaxed);
                    state.stats.requests_total.store(0, Ordering::Relaxed);
                    state.stats.bytes_out.store(0, Ordering::Relaxed);
                    state.stats.aborted.store(0, Ordering::Relaxed);
                }
                _ => {}
            }
        }
    }
    let fs = state.refresh();
    let payload = json!({
        "frames": fs.total_frames,
        "frame_interval_ms": fs.frame_interval.as_millis() as i64,
        "ttft_ms": state.cfg.ttft_ms.load(Ordering::Relaxed),
        "token_rate": state.cfg.token_rate.load(Ordering::Relaxed),
        "tokens_per_frame": state.cfg.tokens_per_frame.load(Ordering::Relaxed),
        "output_tokens": state.cfg.output_tokens.load(Ordering::Relaxed),
        "input_tokens": state.cfg.input_tokens.load(Ordering::Relaxed),
    })
    .to_string();
    build_json_http_response(&payload)
}

fn url_decode_simple(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(b) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                out.push(b);
                i += 3;
                continue;
            }
        } else if bytes[i] == b'+' {
            out.push(b' ');
            i += 1;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

async fn handle_connection(mut stream: TcpStream, state: Arc<AppState>) {
    let _ = stream.set_nodelay(true);
    let mut buf = BytesMut::with_capacity(8192);
    let mut scratch = vec![0u8; READ_BUF_CAP];

    loop {
        // Wait for complete HTTP request headers.
        let header_end = loop {
            if let Some(end) = find_header_end(&buf) {
                break end;
            }
            if buf.len() > 64 * 1024 {
                return;
            }
            match stream.read_buf(&mut buf).await {
                Ok(0) | Err(_) => return,
                Ok(_) => {}
            }
        };

        let parsed = match parse_headers(&buf, header_end) {
            Ok(p) => p,
            Err(_) => return,
        };
        let _ = buf.split_to(parsed.header_end);

        if parsed.expect_continue && stream.write_all(CONTINUE_100).await.is_err() {
            return;
        }

        let is_stream = if parsed.chunked {
            match drain_chunked_body(&mut stream, &mut buf, &state).await {
                Ok(v) => v,
                Err(_) => return,
            }
        } else if let Some(cl) = parsed.content_length {
            if cl > 0 {
                match drain_fixed_body(&mut stream, &mut buf, cl, &state, &mut scratch).await {
                    Ok(v) => v,
                    Err(_) => return,
                }
            } else {
                false
            }
        } else {
            false
        };

        let (path, query) = parsed
            .path_and_query
            .split_once('?')
            .unwrap_or((parsed.path_and_query.as_str(), ""));

        match path {
            "/healthz" => {
                if stream.write_all(HEALTHZ_RESP).await.is_err() {
                    return;
                }
            }
            "/stats" => {
                let resp = handle_stats_response(&state);
                if stream.write_all(&resp).await.is_err() {
                    return;
                }
            }
            "/_mock/config" => {
                let resp = handle_config_response(&state, query);
                if stream.write_all(&resp).await.is_err() {
                    return;
                }
            }
            "/v1/chat/completions" => {
                state.stats.requests_total.fetch_add(1, Ordering::Relaxed);
                let fs = state.current_frame_set();
                let ttft = Duration::from_millis(
                    state.cfg.ttft_ms.load(Ordering::Relaxed).max(0) as u64,
                );

                if !is_stream {
                    let gen_time = fs
                        .frame_interval
                        .saturating_mul(fs.total_frames.max(0) as u32);
                    let total_wait = ttft + gen_time;
                    tokio::select! {
                        biased;
                        _ = wait_peer_closed(&stream) => {
                            state.stats.aborted.fetch_add(1, Ordering::Relaxed);
                            return;
                        }
                        _ = tokio::time::sleep(total_wait) => {}
                    }
                    if stream.write_all(&fs.non_stream_resp).await.is_err() {
                        state.stats.aborted.fetch_add(1, Ordering::Relaxed);
                        return;
                    }
                    state
                        .stats
                        .bytes_out
                        .fetch_add(fs.non_stream_raw_len, Ordering::Relaxed);
                } else {
                    if stream.write_all(STREAM_HEADERS).await.is_err() {
                        state.stats.aborted.fetch_add(1, Ordering::Relaxed);
                        return;
                    }
                    let _guard = ActiveStreamGuard::new(&state.stats.streams_active);
                    state.stats.streams_total.fetch_add(1, Ordering::Relaxed);

                    tokio::select! {
                        biased;
                        _ = wait_peer_closed(&stream) => {
                            state.stats.aborted.fetch_add(1, Ordering::Relaxed);
                            return;
                        }
                        _ = tokio::time::sleep(ttft) => {}
                    }

                    let mut local_bytes: i64 = 0;

                    // Frame 0 immediately after TTFT sleep (no premature ticker overhead).
                    if stream.write_all(&fs.chunk_first).await.is_err() {
                        state.stats.aborted.fetch_add(1, Ordering::Relaxed);
                        return;
                    }
                    local_bytes += fs.first_raw_len;

                    if fs.total_frames > 1 {
                        let start_next = Instant::now() + fs.frame_interval;
                        let mut ticker = tokio::time::interval_at(start_next, fs.frame_interval);
                        ticker.set_missed_tick_behavior(MissedTickBehavior::Skip);

                        for _ in 1..fs.total_frames {
                            tokio::select! {
                                biased;
                                _ = wait_peer_closed(&stream) => {
                                    state.stats.aborted.fetch_add(1, Ordering::Relaxed);
                                    state.stats.bytes_out.fetch_add(local_bytes, Ordering::Relaxed);
                                    return;
                                }
                                _ = ticker.tick() => {}
                            }
                            if stream.write_all(&fs.chunk_delta).await.is_err() {
                                state.stats.aborted.fetch_add(1, Ordering::Relaxed);
                                state.stats.bytes_out.fetch_add(local_bytes, Ordering::Relaxed);
                                return;
                            }
                            local_bytes += fs.delta_raw_len;
                        }
                    }

                    if stream.write_all(&fs.chunk_end).await.is_err() {
                        state.stats.aborted.fetch_add(1, Ordering::Relaxed);
                        state.stats.bytes_out.fetch_add(local_bytes, Ordering::Relaxed);
                        return;
                    }
                    local_bytes += fs.end_raw_len;
                    state.stats.bytes_out.fetch_add(local_bytes, Ordering::Relaxed);
                }
            }
            _ => {
                if stream.write_all(NOT_FOUND_RESP).await.is_err() {
                    return;
                }
            }
        }

        if !parsed.keep_alive {
            return;
        }
    }
}

fn main() {
    let flags = match Flags::parse(std::env::args()) {
        Ok(f) => f,
        Err(msg) if msg == "help" => {
            eprintln!("Usage: mock-upstream [-listen 127.0.0.1:18080] [-ttft-ms 500] [-token-rate 200] [-tokens-per-frame 5] [-output-tokens 2000] [-input-tokens 100000] [-model bench-openai-chat]");
            std::process::exit(0);
        }
        Err(e) => {
            eprintln!("ERROR: {e}");
            std::process::exit(2);
        }
    };

    let listen = flags.get_str("listen", "127.0.0.1:18080");
    let ttft_ms = flags.get_i64("ttft-ms", 500).unwrap();
    let token_rate = flags.get_i64("token-rate", 200).unwrap();
    let tokens_per_frame = flags.get_i64("tokens-per-frame", 5).unwrap();
    let output_tokens = flags.get_i64("output-tokens", 2000).unwrap();
    let input_tokens = flags.get_i64("input-tokens", 100_000).unwrap();
    let model = flags.get_str("model", "bench-openai-chat");

    let workers = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(2);
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

    let state = Arc::new(AppState::new(
        ttft_ms,
        token_rate,
        tokens_per_frame,
        output_tokens,
        input_tokens,
        model,
    ));

    rt.block_on(async move {
        let mut handles = Vec::new();
        for addr in listen.split(',').map(str::trim).filter(|s| !s.is_empty()) {
            let listener = TcpListener::bind(addr)
                .await
                .unwrap_or_else(|e| panic!("bind {addr}: {e}"));
            eprintln!(
                "mock-upstream (rust, workers={workers}) listening on {addr} (ttft={ttft_ms}ms rate={token_rate} tok/s frame={tokens_per_frame} tok out={output_tokens} in={input_tokens})"
            );
            let st = Arc::clone(&state);
            handles.push(tokio::spawn(async move {
                loop {
                    match listener.accept().await {
                        Ok((stream, _peer)) => {
                            let s = Arc::clone(&st);
                            tokio::spawn(handle_connection(stream, s));
                        }
                        Err(e) => {
                            eprintln!("accept error: {e}");
                            tokio::time::sleep(Duration::from_millis(5)).await;
                        }
                    }
                }
            }));
        }
        for h in handles {
            let _ = h.await;
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frame_set_derives_frames_and_interval() {
        let fs = build_frame_set("bench-openai-chat", 100_000, 2_000, 5, 200);
        assert_eq!(fs.total_frames, 400);
        assert_eq!(fs.frame_interval, Duration::from_millis(25));
        let first = std::str::from_utf8(&fs.chunk_first).unwrap();
        let delta = std::str::from_utf8(&fs.chunk_delta).unwrap();
        let end = std::str::from_utf8(&fs.chunk_end).unwrap();
        let non_stream = std::str::from_utf8(&fs.non_stream_resp).unwrap();
        assert!(first.contains("\"role\":\"assistant\""));
        assert_eq!(delta.matches("token ").count(), 5);
        assert!(end.contains("\"prompt_tokens\":100000"));
        assert!(end.contains("\"completion_tokens\":2000"));
        assert!(end.ends_with("data: [DONE]\n\n\r\n0\r\n\r\n"));
        assert!(non_stream.contains("\"object\":\"chat.completion\""));
    }

    #[tokio::test]
    async fn serves_streaming_nonstreaming_config_and_large_body_keepalive() {
        let state = Arc::new(AppState::new(1, 1000, 1, 5, 1000, "bench-chat".into()));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let st = Arc::clone(&state);
        tokio::spawn(async move {
            while let Ok((s, _)) = listener.accept().await {
                tokio::spawn(handle_connection(s, Arc::clone(&st)));
            }
        });

        let mut client = TcpStream::connect(addr).await.unwrap();

        // 1. /healthz
        client
            .write_all(b"GET /healthz HTTP/1.1\r\nHost: localhost\r\n\r\n")
            .await
            .unwrap();
        let mut resp = vec![0u8; 4096];
        let n = client.read(&mut resp).await.unwrap();
        assert!(std::str::from_utf8(&resp[..n]).unwrap().ends_with("ok"));

        // 2. Large 300KB body with "stream": true at the tail (same keep-alive socket)
        let pad = "x".repeat(300 * 1024);
        let body = format!("{{\"model\":\"bench-chat\",\"pad\":\"{pad}\",\"stream\": true}}");
        let req = format!(
            "POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nContent-Length: {}\r\n\r\n{}",
            body.len(),
            body
        );
        client.write_all(req.as_bytes()).await.unwrap();
        let mut stream_out = Vec::new();
        loop {
            let n = client.read(&mut resp).await.unwrap();
            assert!(n > 0);
            stream_out.extend_from_slice(&resp[..n]);
            if memchr::memmem::find(&stream_out, b"\r\n0\r\n\r\n").is_some() {
                break;
            }
        }
        let s = String::from_utf8(stream_out).unwrap();
        assert!(s.contains("text/event-stream"));
        assert!(s.contains("data: [DONE]"));

        // 3. /_mock/config update + non-streaming request on the same socket
        client
            .write_all(
                b"POST /_mock/config?ttft_ms=1&token_rate=2000&tokens_per_frame=2&output_tokens=6&input_tokens=5000 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n",
            )
            .await
            .unwrap();
        let n = client.read(&mut resp).await.unwrap();
        let cfg_resp = std::str::from_utf8(&resp[..n]).unwrap();
        assert!(cfg_resp.contains("\"frames\":3"));

        let ns_body = r#"{"model":"bench-chat","stream":false}"#;
        let ns_req = format!(
            "POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nContent-Length: {}\r\n\r\n{}",
            ns_body.len(),
            ns_body
        );
        client.write_all(ns_req.as_bytes()).await.unwrap();
        let n = client.read(&mut resp).await.unwrap();
        let ns_resp = std::str::from_utf8(&resp[..n]).unwrap();
        assert!(ns_resp.contains("application/json"));
        assert!(ns_resp.contains("\"prompt_tokens\":5000"));
    }
}
