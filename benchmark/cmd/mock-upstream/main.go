package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

type runtimeConfig struct {
	ttftMs     atomic.Int64
	chunks     atomic.Int64
	intervalMs atomic.Int64
	coalesce   atomic.Bool
}

type serverStats struct {
	requestsTotal atomic.Int64
	streamsActive atomic.Int64
	streamsTotal  atomic.Int64
	abortedTotal  atomic.Int64
	bytesIn       atomic.Int64
	bytesOut      atomic.Int64
}

var (
	cfg   runtimeConfig
	stats serverStats

	bufPool = sync.Pool{
		New: func() any {
			b := make([]byte, 8*1024)
			return &b
		},
	}

	// Pre-serialized static wire responses for zero-allocation hot path
	openAiChatNonStreamResp = []byte(`{"id":"chatcmpl-bench-001","object":"chat.completion","created":1730000000,"model":"gpt-bench","choices":[{"index":0,"message":{"role":"assistant","content":"HyperLLM benchmark response ok."},"finish_reason":"stop"}],"usage":{"prompt_tokens":128,"completion_tokens":64,"total_tokens":192,"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":0}}}`)

	openAiChatFirstChunk = []byte("data: {\"id\":\"chatcmpl-bench-001\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,\"model\":\"gpt-bench\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hello \"},\"finish_reason\":null}]}\n\n")
	openAiChatDeltaChunk = []byte("data: {\"id\":\"chatcmpl-bench-001\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,\"model\":\"gpt-bench\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"bench token \"},\"finish_reason\":null}]}\n\n")
	openAiChatEndFrames  = []byte(
		"data: {\"id\":\"chatcmpl-bench-001\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,\"model\":\"gpt-bench\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
			"data: {\"id\":\"chatcmpl-bench-001\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,\"model\":\"gpt-bench\",\"choices\":[],\"usage\":{\"prompt_tokens\":128,\"completion_tokens\":64,\"total_tokens\":192,\"prompt_tokens_details\":{\"cached_tokens\":0},\"completion_tokens_details\":{\"reasoning_tokens\":0}}}\n\n" +
			"data: [DONE]\n\n")

	openAiRespNonStreamResp = []byte(`{"id":"resp_bench_001","object":"response","created_at":1730000000,"status":"completed","model":"gpt-bench-resp","output":[{"id":"msg_bench_001","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"HyperLLM responses benchmark ok."}]}],"usage":{"input_tokens":128,"output_tokens":64,"total_tokens":192,"input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}}`)

	openAiRespStartFrames = []byte(
		"event: response.created\ndata: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_bench_001\",\"object\":\"response\",\"created_at\":1730000000,\"status\":\"in_progress\",\"model\":\"gpt-bench-resp\",\"output\":[]}}\n\n" +
			"event: response.output_item.added\ndata: {\"type\":\"response.output_item.added\",\"sequence_number\":1,\"output_index\":0,\"item\":{\"id\":\"msg_bench_001\",\"type\":\"message\",\"status\":\"in_progress\",\"role\":\"assistant\",\"content\":[]}}\n\n" +
			"event: response.content_part.added\ndata: {\"type\":\"response.content_part.added\",\"sequence_number\":2,\"item_id\":\"msg_bench_001\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"\"}}\n\n")
	openAiRespDeltaFrame = []byte("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_bench_001\",\"output_index\":0,\"content_index\":0,\"delta\":\"bench token \"}\n\n")
	openAiRespEndFrames  = []byte(
		"event: response.output_text.done\ndata: {\"type\":\"response.output_text.done\",\"item_id\":\"msg_bench_001\",\"output_index\":0,\"content_index\":0,\"text\":\"done\"}\n\n" +
			"event: response.output_item.done\ndata: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"id\":\"msg_bench_001\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]}}\n\n" +
			"event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_bench_001\",\"object\":\"response\",\"created_at\":1730000000,\"status\":\"completed\",\"model\":\"gpt-bench-resp\",\"output\":[],\"usage\":{\"input_tokens\":128,\"output_tokens\":64,\"total_tokens\":192,\"input_tokens_details\":{\"cached_tokens\":0},\"output_tokens_details\":{\"reasoning_tokens\":0}}}}\n\n")

	anthropicNonStreamResp = []byte(`{"id":"msg_bench_001","type":"message","role":"assistant","model":"claude-bench","content":[{"type":"text","text":"HyperLLM anthropic benchmark ok."}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":128,"output_tokens":64}}`)

	anthropicStartFrames = []byte(
		"event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_bench_001\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-bench\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":128,\"output_tokens\":1}}}\n\n" +
			"event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n")
	anthropicDeltaFrame = []byte("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"bench token \"}}\n\n")
	anthropicEndFrames  = []byte(
		"event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
			"event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":64}}\n\n" +
			"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")

	geminiNonStreamResp = []byte(`{"responseId":"gem_bench_001","modelVersion":"gemini-bench","candidates":[{"index":0,"content":{"role":"model","parts":[{"text":"HyperLLM gemini benchmark ok."}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":128,"candidatesTokenCount":64,"totalTokenCount":192}}`)

	geminiDeltaFrame = []byte("data: {\"responseId\":\"gem_bench_001\",\"modelVersion\":\"gemini-bench\",\"candidates\":[{\"index\":0,\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"bench token \"}]}}]}\n\n")
	geminiEndFrame   = []byte("data: {\"responseId\":\"gem_bench_001\",\"modelVersion\":\"gemini-bench\",\"candidates\":[{\"index\":0,\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"done\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":128,\"candidatesTokenCount\":64,\"totalTokenCount\":192}}\n\n")
)

func main() {
	listenFlag := flag.String("listen", "127.0.0.1:18080", "Comma-separated listen addresses")
	ttftMs := flag.Int64("ttft-ms", 0, "Default TTFT in milliseconds")
	chunks := flag.Int64("chunks", 20, "Default number of SSE delta chunks")
	intervalMs := flag.Int64("interval-ms", 0, "Default delay between SSE chunks in milliseconds")
	coalesce := flag.Bool("coalesce", false, "If true, flush only at start and end when interval-ms == 0")
	flag.Parse()

	cfg.ttftMs.Store(*ttftMs)
	cfg.chunks.Store(*chunks)
	cfg.intervalMs.Store(*intervalMs)
	cfg.coalesce.Store(*coalesce)

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", handleHealth)
	mux.HandleFunc("/stats", handleStats)
	mux.HandleFunc("/_mock/config", handleConfig)
	mux.HandleFunc("/v1/chat/completions", handleOpenAiChat)
	mux.HandleFunc("/v1/responses", handleOpenAiResponses)
	mux.HandleFunc("/v1/messages", handleAnthropicMessages)
	mux.HandleFunc("/v1beta/models/", handleGemini)

	addrs := strings.Split(*listenFlag, ",")
	var servers []*http.Server
	for _, rawAddr := range addrs {
		addr := strings.TrimSpace(rawAddr)
		if addr == "" {
			continue
		}
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			log.Fatalf("failed to listen on %s: %v", addr, err)
		}
		srv := &http.Server{
			Handler:                      mux,
			IdleTimeout:                  120 * time.Second,
			ReadHeaderTimeout:            10 * time.Second,
			DisableGeneralOptionsHandler: true,
		}
		servers = append(servers, srv)
		go func(a string, l net.Listener, s *http.Server) {
			log.Printf("mock-upstream listening on %s (ttft=%dms chunks=%d interval=%dms)",
				a, cfg.ttftMs.Load(), cfg.chunks.Load(), cfg.intervalMs.Load())
			if err := s.Serve(l); err != nil && err != http.ErrServerClosed {
				log.Fatalf("server error on %s: %v", a, err)
			}
		}(addr, ln, srv)
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for _, srv := range servers {
		_ = srv.Shutdown(ctx)
	}
}

func handleHealth(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

func handleStats(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	resp := map[string]any{
		"requests_total": stats.requestsTotal.Load(),
		"streams_active": stats.streamsActive.Load(),
		"streams_total":  stats.streamsTotal.Load(),
		"aborted_total":  stats.abortedTotal.Load(),
		"bytes_in":       stats.bytesIn.Load(),
		"bytes_out":      stats.bytesOut.Load(),
		"config": map[string]any{
			"ttft_ms":     cfg.ttftMs.Load(),
			"chunks":      cfg.chunks.Load(),
			"interval_ms": cfg.intervalMs.Load(),
			"coalesce":    cfg.coalesce.Load(),
		},
	}
	_ = json.NewEncoder(w).Encode(resp)
}

func handleConfig(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	if v := q.Get("ttft_ms"); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n >= 0 {
			cfg.ttftMs.Store(n)
		}
	}
	if v := q.Get("chunks"); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n >= 1 {
			cfg.chunks.Store(n)
		}
	}
	if v := q.Get("interval_ms"); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n >= 0 {
			cfg.intervalMs.Store(n)
		}
	}
	if v := q.Get("coalesce"); v != "" {
		cfg.coalesce.Store(v == "1" || strings.EqualFold(v, "true"))
	}
	if q.Get("reset_stats") == "1" {
		stats.requestsTotal.Store(0)
		stats.streamsTotal.Store(0)
		stats.abortedTotal.Store(0)
		stats.bytesIn.Store(0)
		stats.bytesOut.Store(0)
	}
	handleStats(w, r)
}

type reqParams struct {
	ttftMs     int64
	chunks     int64
	intervalMs int64
	coalesce   bool
}

func resolveParams(r *http.Request) reqParams {
	p := reqParams{
		ttftMs:     cfg.ttftMs.Load(),
		chunks:     cfg.chunks.Load(),
		intervalMs: cfg.intervalMs.Load(),
		coalesce:   cfg.coalesce.Load(),
	}
	if h := r.Header.Get("X-Mock-TTFT-Ms"); h != "" {
		if n, err := strconv.ParseInt(h, 10, 64); err == nil && n >= 0 {
			p.ttftMs = n
		}
	}
	if h := r.Header.Get("X-Mock-Chunks"); h != "" {
		if n, err := strconv.ParseInt(h, 10, 64); err == nil && n >= 1 {
			p.chunks = n
		}
	}
	if h := r.Header.Get("X-Mock-Interval-Ms"); h != "" {
		if n, err := strconv.ParseInt(h, 10, 64); err == nil && n >= 0 {
			p.intervalMs = n
		}
	}
	return p
}

// detectStreamAndDrain reads the request body using a pooled buffer with zero per-request heap allocation,
// detecting `"stream":true` or `"stream": true` even across chunk boundaries.
func detectStreamAndDrain(body io.ReadCloser) bool {
	if body == nil {
		return false
	}
	defer body.Close()

	bufPtr := bufPool.Get().(*[]byte)
	buf := *bufPtr
	defer bufPool.Put(bufPtr)

	const overlap = 32
	isStream := false
	carry := 0
	var total int64

	for {
		n, err := body.Read(buf[carry:])
		if n > 0 {
			total += int64(n)
			if !isStream {
				window := buf[:carry+n]
				if containsStreamTrue(window) {
					isStream = true
				} else if len(window) > overlap {
					copy(buf[:overlap], window[len(window)-overlap:])
					carry = overlap
				} else {
					carry = len(window)
				}
			} else {
				carry = 0
			}
		}
		if err != nil {
			break
		}
	}
	stats.bytesIn.Add(total)
	return isStream
}

var (
	streamKeyBytes = []byte(`"stream"`)
	trueBytes      = []byte("true")
)

func containsStreamTrue(b []byte) bool {
	offset := 0
	for {
		idx := bytes.Index(b[offset:], streamKeyBytes)
		if idx < 0 {
			return false
		}
		pos := offset + idx + len(streamKeyBytes)
		for pos < len(b) && (b[pos] == ' ' || b[pos] == '\t' || b[pos] == '\r' || b[pos] == '\n') {
			pos++
		}
		if pos < len(b) && b[pos] == ':' {
			pos++
			for pos < len(b) && (b[pos] == ' ' || b[pos] == '\t' || b[pos] == '\r' || b[pos] == '\n') {
				pos++
			}
			if pos+len(trueBytes) <= len(b) && bytes.Equal(b[pos:pos+len(trueBytes)], trueBytes) {
				return true
			}
		}
		offset = offset + idx + len(streamKeyBytes)
	}
}

func sleepContext(ctx context.Context, d time.Duration) bool {
	if d <= 0 {
		return ctx.Err() == nil
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

func writeNonStream(w http.ResponseWriter, r *http.Request, payload []byte) {
	p := resolveParams(r)
	if !sleepContext(r.Context(), time.Duration(p.ttftMs)*time.Millisecond) {
		stats.abortedTotal.Add(1)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
	w.WriteHeader(http.StatusOK)
	n, _ := w.Write(payload)
	stats.bytesOut.Add(int64(n))
}

func writeStream(w http.ResponseWriter, r *http.Request, startFrames []byte, deltaFrame []byte, endFrames []byte) {
	p := resolveParams(r)
	stats.streamsTotal.Add(1)
	stats.streamsActive.Add(1)
	defer stats.streamsActive.Add(-1)

	ctx := r.Context()
	if !sleepContext(ctx, time.Duration(p.ttftMs)*time.Millisecond) {
		stats.abortedTotal.Add(1)
		return
	}

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.WriteHeader(http.StatusOK)

	var outBytes int64
	defer func() {
		stats.bytesOut.Add(outBytes)
	}()

	writeFrame := func(frame []byte, flushNow bool) bool {
		if len(frame) == 0 {
			return true
		}
		n, err := w.Write(frame)
		outBytes += int64(n)
		if err != nil {
			stats.abortedTotal.Add(1)
			return false
		}
		if flushNow {
			flusher.Flush()
		}
		return true
	}

	flushEach := p.intervalMs > 0 || !p.coalesce
	if !writeFrame(startFrames, true) {
		return
	}

	interval := time.Duration(p.intervalMs) * time.Millisecond
	for i := int64(0); i < p.chunks; i++ {
		if interval > 0 {
			if !sleepContext(ctx, interval) {
				stats.abortedTotal.Add(1)
				return
			}
		} else if ctx.Err() != nil {
			stats.abortedTotal.Add(1)
			return
		}
		if !writeFrame(deltaFrame, flushEach) {
			return
		}
	}

	if !writeFrame(endFrames, true) {
		return
	}
}

func handleOpenAiChat(w http.ResponseWriter, r *http.Request) {
	stats.requestsTotal.Add(1)
	isStream := detectStreamAndDrain(r.Body)
	if !isStream {
		writeNonStream(w, r, openAiChatNonStreamResp)
		return
	}
	writeStream(w, r, openAiChatFirstChunk, openAiChatDeltaChunk, openAiChatEndFrames)
}

func handleOpenAiResponses(w http.ResponseWriter, r *http.Request) {
	stats.requestsTotal.Add(1)
	isStream := detectStreamAndDrain(r.Body)
	if !isStream {
		writeNonStream(w, r, openAiRespNonStreamResp)
		return
	}
	writeStream(w, r, openAiRespStartFrames, openAiRespDeltaFrame, openAiRespEndFrames)
}

func handleAnthropicMessages(w http.ResponseWriter, r *http.Request) {
	stats.requestsTotal.Add(1)
	isStream := detectStreamAndDrain(r.Body)
	if !isStream {
		writeNonStream(w, r, anthropicNonStreamResp)
		return
	}
	writeStream(w, r, anthropicStartFrames, anthropicDeltaFrame, anthropicEndFrames)
}

func handleGemini(w http.ResponseWriter, r *http.Request) {
	stats.requestsTotal.Add(1)
	_ = detectStreamAndDrain(r.Body)
	if strings.Contains(r.URL.Path, ":streamGenerateContent") {
		writeStream(w, r, nil, geminiDeltaFrame, geminiEndFrame)
		return
	}
	if strings.Contains(r.URL.Path, ":generateContent") {
		writeNonStream(w, r, geminiNonStreamResp)
		return
	}
	w.WriteHeader(http.StatusNotFound)
	_, _ = fmt.Fprintf(w, `{"error":{"code":404,"message":"unknown gemini path: %s"}}`, r.URL.Path)
}
