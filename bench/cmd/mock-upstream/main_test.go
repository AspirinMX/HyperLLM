package main

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestContainsStreamTrueAndDrain(t *testing.T) {
	pad := strings.Repeat("a", 8185)
	payload := `{"model":"gpt-bench","pad":"` + pad + `","stream": true,"messages":[]}`
	if !detectStreamAndDrain(io.NopCloser(strings.NewReader(payload))) {
		t.Fatalf("expected stream=true to be detected across 8KB chunk boundary")
	}
	nonStream := `{"model":"gpt-bench","stream":false}`
	if detectStreamAndDrain(io.NopCloser(strings.NewReader(nonStream))) {
		t.Fatalf("expected stream=false not to be detected as stream")
	}
}

func TestAllFourProtocolsNonStreamAndStream(t *testing.T) {
	cfg.ttftMs.Store(0)
	cfg.chunks.Store(3)
	cfg.intervalMs.Store(0)

	cases := []struct {
		name     string
		handler  http.HandlerFunc
		path     string
		body     string
		wantCT   string
		wantText string
	}{
		{"openai-chat-ns", handleOpenAiChat, "/v1/chat/completions", `{"model":"gpt-bench","stream":false}`, "application/json", `"prompt_tokens":128`},
		{"openai-chat-sse", handleOpenAiChat, "/v1/chat/completions", `{"model":"gpt-bench","stream":true}`, "text/event-stream", `data: [DONE]`},
		{"openai-resp-ns", handleOpenAiResponses, "/v1/responses", `{"model":"gpt-bench-resp","stream":false}`, "application/json", `"input_tokens":128`},
		{"openai-resp-sse", handleOpenAiResponses, "/v1/responses", `{"model":"gpt-bench-resp","stream":true}`, "text/event-stream", `event: response.completed`},
		{"anthropic-ns", handleAnthropicMessages, "/v1/messages", `{"model":"claude-bench","stream":false}`, "application/json", `"output_tokens":64`},
		{"anthropic-sse", handleAnthropicMessages, "/v1/messages", `{"model":"claude-bench","stream":true}`, "text/event-stream", `event: message_stop`},
		{"gemini-ns", handleGemini, "/v1beta/models/gemini-bench:generateContent", `{}`, "application/json", `"promptTokenCount":128`},
		{"gemini-sse", handleGemini, "/v1beta/models/gemini-bench:streamGenerateContent?alt=sse", `{}`, "text/event-stream", `"finishReason":"STOP"`},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodPost, tc.path, bytes.NewBufferString(tc.body))
			rec := httptest.NewRecorder()
			tc.handler(rec, req)
			if rec.Code != http.StatusOK {
				t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
			}
			if ct := rec.Header().Get("Content-Type"); !strings.Contains(ct, tc.wantCT) {
				t.Fatalf("content-type=%q want %q", ct, tc.wantCT)
			}
			if !strings.Contains(rec.Body.String(), tc.wantText) {
				t.Fatalf("body missing %q: %s", tc.wantText, rec.Body.String())
			}
		})
	}
}
