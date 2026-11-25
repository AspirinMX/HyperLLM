// Command mock-upstream is a minimal, fully configurable OpenAI Chat Completions upstream used by
// the benchmark suite. It only speaks POST /v1/chat/completions (streaming and non-streaming) because
// every scenario under test keeps the upstream protocol fixed to OpenAI Chat; the downstream protocol
// (client side) is what varies between the same-protocol and cross-protocol pairs.
//
// The response shape is described by five numbers, all configurable at startup and at runtime through
// POST /_mock/config: upstream TTFT, output tokens, token rate, tokens per SSE frame and the prompt
// token count reported in usage. Defaults model a 100K-token agent request: 500ms TTFT, 2000 output
// tokens at 200 tok/s in 5-token frames (400 frames, one every 25ms, ~10s per stream).
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

// runtimeConfig is the mutable shape of the mock; every field is safe to change while it serves.
type runtimeConfig struct {
	ttftMs        atomic.Int64
	tokenRate     atomic.Int64 // output tokens per second
	tokensPerFrame atomic.Int64
	outputTokens  atomic.Int64
	inputTokens   atomic.Int64
}

var cfg runtimeConfig

// modelName is echoed back in every response so the upstream looks like the channel it stands for.
var modelName atomic.Value

// frameSet holds the pre-serialized SSE bytes for the current configuration.
type frameSet struct {
	frames       [][]byte // delta frames, one per SSE event
	endFrames    []byte   // finish_reason + usage + [DONE]
	nonStream    []byte   // single non-streaming JSON body
	totalFrames  int64
	frameInterval time.Duration
}

var current atomic.Pointer[frameSet]

type serverStats struct {
	streamsActive atomic.Int64
	streamsTotal  atomic.Int64
	requestsTotal atomic.Int64
	bytesOut      atomic.Int64
	aborted       atomic.Int64
}

var stats serverStats

func buildFrameSet(model string, inputTokens, outputTokens, tokensPerFrame, tokenRate int64) *frameSet {
	if tokensPerFrame < 1 {
		tokensPerFrame = 1
	}
	if tokenRate < 1 {
		tokenRate = 1
	}
	frames := outputTokens / tokensPerFrame
	if frames < 1 {
		frames = 1
	}
	if outputTokens < tokensPerFrame {
		outputTokens = tokensPerFrame
	}
	frameText := strings.Repeat("token ", int(tokensPerFrame))
	usage := fmt.Sprintf("{\"prompt_tokens\":%d,\"completion_tokens\":%d,\"total_tokens\":%d,"+
		"\"prompt_tokens_details\":{\"cached_tokens\":0},"+
		"\"completion_tokens_details\":{\"reasoning_tokens\":0}}", inputTokens, outputTokens, inputTokens+outputTokens)

	first := fmt.Sprintf("data: {\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,"+
		"\"model\":%q,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":%q},"+
		"\"finish_reason\":null}]}\n\n", model, frameText)
	delta := fmt.Sprintf("data: {\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,"+
		"\"model\":%q,\"choices\":[{\"index\":0,\"delta\":{\"content\":%q},"+
		"\"finish_reason\":null}]}\n\n", model, frameText)
	end := fmt.Sprintf("data: {\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,"+
		"\"model\":%q,\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n", model) +
		fmt.Sprintf("data: {\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,"+
			"\"model\":%q,\"choices\":[],\"usage\":%s}\n\ndata: [DONE]\n\n", model, usage)
	nonStream := fmt.Sprintf("{\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion\",\"created\":1730000000,"+
		"\"model\":%q,\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":%q},"+
		"\"finish_reason\":\"stop\"}],\"usage\":%s}",
		model, strings.Repeat("token ", int(outputTokens)), usage)

	out := make([][]byte, frames)
	for i := range out {
		if i == 0 {
			out[i] = []byte(first)
		} else {
			out[i] = []byte(delta)
		}
	}
	interval := time.Duration(float64(tokensPerFrame) / float64(tokenRate) * float64(time.Second))
	return &frameSet{
		frames:        out,
		endFrames:     []byte(end),
		nonStream:     []byte(nonStream),
		totalFrames:   frames,
		frameInterval: interval,
	}
}

func refresh() {
	model, _ := modelName.Load().(string)
	if model == "" {
		model = "bench-openai-chat"
	}
	current.Store(buildFrameSet(model, cfg.inputTokens.Load(), cfg.outputTokens.Load(),
		cfg.tokensPerFrame.Load(), cfg.tokenRate.Load()))
}

func main() {
	listen := flag.String("listen", "127.0.0.1:18080", "Comma-separated listen addresses")
	ttftMs := flag.Int64("ttft-ms", 500, "Upstream time to first token in milliseconds")
	tokenRate := flag.Int64("token-rate", 200, "Output tokens per second")
	tokensPerFrame := flag.Int64("tokens-per-frame", 5, "Tokens carried by one SSE frame")
	outputTokens := flag.Int64("output-tokens", 2000, "Output tokens per response")
	inputTokens := flag.Int64("input-tokens", 100000, "Prompt tokens reported in usage")
	model := flag.String("model", "bench-openai-chat", "Model name echoed in responses")
	flag.Parse()

	cfg.ttftMs.Store(*ttftMs)
	cfg.tokenRate.Store(*tokenRate)
	cfg.tokensPerFrame.Store(*tokensPerFrame)
	cfg.outputTokens.Store(*outputTokens)
	cfg.inputTokens.Store(*inputTokens)
	modelName.Store(*model)
	refresh()

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("/stats", handleStats)
	mux.HandleFunc("/_mock/config", handleConfig)
	mux.HandleFunc("/v1/chat/completions", handleChatCompletions)

	for _, addr := range strings.Split(*listen, ",") {
		addr = strings.TrimSpace(addr)
		if addr == "" {
			continue
		}
		go func(a string) {
			srv := &http.Server{Addr: a, Handler: mux, ReadHeaderTimeout: 10 * time.Second}
			log.Printf("mock-upstream listening on %s (ttft=%dms rate=%d tok/s frame=%d tok out=%d in=%d)",
				a, cfg.ttftMs.Load(), cfg.tokenRate.Load(), cfg.tokensPerFrame.Load(),
				cfg.outputTokens.Load(), cfg.inputTokens.Load())
			if err := srv.ListenAndServe(); err != nil {
				log.Fatalf("listen %s: %v", a, err)
			}
		}(addr)
	}
	select {}
}

func handleStats(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"streams_active": stats.streamsActive.Load(),
		"streams_total":  stats.streamsTotal.Load(),
		"requests_total": stats.requestsTotal.Load(),
		"bytes_out":      stats.bytesOut.Load(),
		"aborted_total":  stats.aborted.Load(),
		"config": map[string]any{
			"ttft_ms":          cfg.ttftMs.Load(),
			"token_rate":       cfg.tokenRate.Load(),
			"tokens_per_frame": cfg.tokensPerFrame.Load(),
			"output_tokens":    cfg.outputTokens.Load(),
			"input_tokens":     cfg.inputTokens.Load(),
			"model":            modelName.Load(),
		},
	})
}

func handleConfig(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	set := func(name string, dst *atomic.Int64) {
		if v := q.Get(name); v != "" {
			if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > 0 {
				dst.Store(n)
			}
		}
	}
	set("ttft_ms", &cfg.ttftMs)
	set("token_rate", &cfg.tokenRate)
	set("tokens_per_frame", &cfg.tokensPerFrame)
	set("output_tokens", &cfg.outputTokens)
	set("input_tokens", &cfg.inputTokens)
	if v := q.Get("model"); v != "" {
		modelName.Store(v)
	}
	if q.Get("reset_stats") == "1" {
		stats.streamsTotal.Store(0)
		stats.requestsTotal.Store(0)
		stats.bytesOut.Store(0)
		stats.aborted.Store(0)
	}
	refresh()
	fs := current.Load()
	_ = json.NewEncoder(w).Encode(map[string]any{
		"frames":         fs.totalFrames,
		"frame_interval_ms": fs.frameInterval.Milliseconds(),
		"ttft_ms":        cfg.ttftMs.Load(),
		"token_rate":     cfg.tokenRate.Load(),
		"tokens_per_frame": cfg.tokensPerFrame.Load(),
		"output_tokens":  cfg.outputTokens.Load(),
		"input_tokens":   cfg.inputTokens.Load(),
	})
}

// handleChatCompletions drains the request body (the payload size is what the gateway has to parse) and
// emits either one JSON body or a configured stream of SSE frames.
func handleChatCompletions(w http.ResponseWriter, r *http.Request) {
	stats.requestsTotal.Add(1)
	stream := drainAndDetectStream(r)

	fs := current.Load()
	if !stream {
		select {
		case <-time.After(time.Duration(cfg.ttftMs.Load()) * time.Millisecond):
		case <-r.Context().Done():
			stats.aborted.Add(1)
			return
		}
		select {
		case <-time.After(fs.frameInterval * time.Duration(fs.totalFrames)):
		case <-r.Context().Done():
			stats.aborted.Add(1)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(fs.nonStream)
		stats.bytesOut.Add(int64(len(fs.nonStream)))
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
	flusher.Flush()

	stats.streamsActive.Add(1)
	stats.streamsTotal.Add(1)
	defer stats.streamsActive.Add(-1)

	ticker := time.NewTicker(fs.frameInterval)
	defer ticker.Stop()

	select {
	case <-time.After(time.Duration(cfg.ttftMs.Load()) * time.Millisecond):
	case <-r.Context().Done():
		stats.aborted.Add(1)
		return
	}

	for i := int64(0); i < fs.totalFrames; i++ {
		if i > 0 {
			select {
			case <-ticker.C:
			case <-r.Context().Done():
				stats.aborted.Add(1)
				return
			}
		}
		if _, err := w.Write(fs.frames[i]); err != nil {
			stats.aborted.Add(1)
			return
		}
		stats.bytesOut.Add(int64(len(fs.frames[i])))
		flusher.Flush()
	}
	if _, err := w.Write(fs.endFrames); err != nil {
		stats.aborted.Add(1)
		return
	}
	stats.bytesOut.Add(int64(len(fs.endFrames)))
	flusher.Flush()
}

// drainAndDetectStream consumes the whole request body (a real upstream would) while looking for the
// top-level "stream": true flag. Scanning in blocks with a small overlap keeps memory flat for
// 100K-token payloads and avoids assuming where the flag sits inside the JSON.
func drainAndDetectStream(r *http.Request) bool {
	const block = 64 << 10
	const overlap = 64
	buf := make([]byte, block+overlap)
	want := []string{"\"stream\":true", "\"stream\": true"}
	carry := 0
	stream := false
	for {
		n, err := r.Body.Read(buf[carry:])
		if n > 0 {
			if !stream {
				window := strings.ToLower(string(buf[:carry+n]))
				for _, w := range want {
					if strings.Contains(window, w) {
						stream = true
						break
					}
				}
			}
			if n > overlap {
				copy(buf[:overlap], buf[carry+n-overlap:carry+n])
				carry = overlap
			} else {
				carry = 0
			}
		}
		if err == io.EOF || err != nil {
			break
		}
	}
	return stream
}
