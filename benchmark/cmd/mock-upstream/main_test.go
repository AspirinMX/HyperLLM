package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// configure applies a small, fast configuration so handler tests do not wait ten seconds.
func configure(t *testing.T, ttft, rate, perFrame, out, in int64) {
	t.Helper()
	cfg.ttftMs.Store(ttft)
	cfg.tokenRate.Store(rate)
	cfg.tokensPerFrame.Store(perFrame)
	cfg.outputTokens.Store(out)
	cfg.inputTokens.Store(in)
	refresh()
}

func TestFrameSetDerivesFramesAndInterval(t *testing.T) {
	fs := buildFrameSet("bench-openai-chat", 100000, 2000, 5, 200)
	if fs.totalFrames != 400 {
		t.Fatalf("expected 400 frames for 2000 tokens at 5 tokens/frame, got %d", fs.totalFrames)
	}
	if fs.frameInterval != 25*time.Millisecond {
		t.Fatalf("expected 25ms frame interval for 200 tok/s at 5 tokens/frame, got %s", fs.frameInterval)
	}
	if len(fs.frames) != 400 {
		t.Fatalf("expected 400 prebuilt frames, got %d", len(fs.frames))
	}
	end := string(fs.endFrames)
	if !strings.Contains(end, "\"prompt_tokens\":100000") || !strings.Contains(end, "\"completion_tokens\":2000") {
		t.Fatalf("usage frame must report configured token counts: %s", end)
	}
	if !strings.Contains(string(fs.nonStream), "\"completion_tokens\":2000") {
		t.Fatalf("non-stream body must report configured usage")
	}
	// The first frame carries the role and the configured tokens-per-frame text.
	if !strings.Contains(string(fs.frames[0]), "\"role\":\"assistant\"") {
		t.Fatalf("first frame must announce the assistant role")
	}
	if strings.Count(string(fs.frames[1]), "token ") != 5 {
		t.Fatalf("delta frame must carry tokens_per_frame tokens")
	}
}

func TestStreamingAndNonStreamingHandlers(t *testing.T) {
	configure(t, 1, 1000, 1, 10, 1000)

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(`{"model":"bench-chat","stream":true,"messages":[]}`))
	handleChatCompletions(rec, req)
	resp := rec.Result()
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Fatalf("streaming response content type = %q", ct)
	}
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "data: [DONE]") {
		t.Fatalf("streaming response must terminate with [DONE]")
	}

	rec = httptest.NewRecorder()
	req = httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(`{"model":"bench-chat","stream":false,"messages":[]}`))
	handleChatCompletions(rec, req)
	resp = rec.Result()
	if ct := resp.Header.Get("Content-Type"); ct != "application/json" {
		t.Fatalf("non-stream response content type = %q", ct)
	}
	body, _ = io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "\"object\":\"chat.completion\"") {
		t.Fatalf("non-stream body: %s", string(body))
	}
}

func TestDrainAndDetectStreamScansLargeBodies(t *testing.T) {
	pad := strings.Repeat("x", 300<<10)
	streamBody := `{"model":"bench-chat","pad":"` + pad + `","stream": true,"messages":[]}`
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(streamBody))
	if !drainAndDetectStream(req) {
		t.Fatalf("expected stream flag to be detected somewhere inside a 300KB body")
	}
	nonStream := `{"model":"bench-chat","stream":false}`
	req = httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(nonStream))
	if drainAndDetectStream(req) {
		t.Fatalf("stream=false must not be treated as streaming")
	}
	_ = rec
}

func TestConfigEndpointUpdatesFrames(t *testing.T) {
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/_mock/config?ttft_ms=250&token_rate=100&tokens_per_frame=2&output_tokens=40&input_tokens=5000", nil)
	handleConfig(rec, req)
	if cfg.ttftMs.Load() != 250 || cfg.tokenRate.Load() != 100 || cfg.tokensPerFrame.Load() != 2 {
		t.Fatalf("config endpoint did not apply values: ttft=%d rate=%d perFrame=%d",
			cfg.ttftMs.Load(), cfg.tokenRate.Load(), cfg.tokensPerFrame.Load())
	}
	fs := current.Load()
	if fs.totalFrames != 20 || fs.frameInterval != 20*time.Millisecond {
		t.Fatalf("rebuilt frames: total=%d interval=%s", fs.totalFrames, fs.frameInterval)
	}
}
