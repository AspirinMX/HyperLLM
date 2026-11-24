package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type config struct {
	target           string
	protocol         string
	model            string
	apiKey           string
	stream           bool
	payloadKB        int
	tools            int
	concurrency      int
	duration         time.Duration
	warmup           time.Duration
	timeout          time.Duration
	rate             float64
	abortAfterChunks int
	slowReadMs       int
	jsonOut          string
	label            string
}

type workerResult struct {
	latenciesNs []int64
	ttftNs      []int64
	tpotNs      []int64
	statusCount map[int]int64
	errCount    int64
	sampleErr   string
	events      int64
	bytesRead   int64
}

type summaryReport struct {
	Label            string        `json:"label"`
	Protocol         string        `json:"protocol"`
	Model            string        `json:"model"`
	Stream           bool          `json:"stream"`
	PayloadBytes     int           `json:"payload_bytes"`
	Concurrency      int           `json:"concurrency"`
	DurationSec      float64       `json:"duration_sec"`
	TotalRequests    int64         `json:"total_requests"`
	SuccessRequests  int64         `json:"success_requests"`
	ErrorRequests    int64         `json:"error_requests"`
	StatusCounts     map[int]int64 `json:"status_counts"`
	SampleError      string        `json:"sample_error,omitempty"`
	RPS              float64       `json:"rps"`
	EventsPerSec     float64       `json:"events_per_sec,omitempty"`
	TotalEvents      int64         `json:"total_events,omitempty"`
	ReadMBPerSec     float64       `json:"read_mb_per_sec"`
	LatencyMs        percentiles   `json:"latency_ms"`
	TTFTMs           *percentiles  `json:"ttft_ms,omitempty"`
	TPOTMs           *percentiles  `json:"tpot_ms,omitempty"`
}

type percentiles struct {
	Min   float64 `json:"min"`
	Mean  float64 `json:"mean"`
	P50   float64 `json:"p50"`
	P90   float64 `json:"p90"`
	P95   float64 `json:"p95"`
	P99   float64 `json:"p99"`
	P999  float64 `json:"p999"`
	Max   float64 `json:"max"`
	Count int     `json:"count"`
}

func main() {
	var cfg config
	flag.StringVar(&cfg.target, "target", "http://127.0.0.1:8080", "Gateway base URL")
	flag.StringVar(&cfg.protocol, "protocol", "openai-chat", "Protocol: openai-chat|openai-responses|anthropic|gemini")
	flag.StringVar(&cfg.model, "model", "bench-openai-chat", "Virtual model code")
	flag.StringVar(&cfg.apiKey, "api-key", "sk-bench0010000000000000000000000000000000000000001", "Gateway API key")
	flag.BoolVar(&cfg.stream, "stream", false, "Enable streaming SSE requests")
	flag.IntVar(&cfg.payloadKB, "payload-kb", 0, "Target request payload size in KB (0 = small ~200B prompt)")
	flag.IntVar(&cfg.tools, "tools", 0, "Number of JSON schema tool definitions to attach")
	flag.IntVar(&cfg.concurrency, "concurrency", 50, "Concurrent worker goroutines")
	flag.DurationVar(&cfg.duration, "duration", 30*time.Second, "Measured benchmark duration")
	flag.DurationVar(&cfg.warmup, "warmup", 5*time.Second, "Warmup duration before recording metrics")
	flag.DurationVar(&cfg.timeout, "timeout", 60*time.Second, "Per-request HTTP timeout")
	flag.Float64Var(&cfg.rate, "rate", 0, "Optional global target RPS (0 = closed-loop max)")
	flag.IntVar(&cfg.abortAfterChunks, "abort-after-chunks", 0, "Abort stream after reading N SSE data frames (0 = disabled)")
	flag.IntVar(&cfg.slowReadMs, "slow-read-ms", 0, "Sleep ms per SSE chunk read to simulate slow consumers")
	flag.StringVar(&cfg.jsonOut, "json-out", "", "Optional path to write JSON summary report")
	flag.StringVar(&cfg.label, "label", "", "Optional benchmark scenario label")
	flag.Parse()

	if cfg.label == "" {
		mode := "non-stream"
		if cfg.stream {
			mode = "stream"
		}
		cfg.label = fmt.Sprintf("%s/%s/%s/c%d/%dkb", cfg.protocol, cfg.model, mode, cfg.concurrency, cfg.payloadKB)
	}

	reqURL, headers, bodyBytes := buildRequestSpec(cfg)

	transport := &http.Transport{
		Proxy: nil,
		DialContext: (&net.Dialer{
			Timeout:   5 * time.Second,
			KeepAlive: 60 * time.Second,
		}).DialContext,
		MaxIdleConns:          cfg.concurrency * 2,
		MaxIdleConnsPerHost:   cfg.concurrency * 2,
		MaxConnsPerHost:       0,
		IdleConnTimeout:       90 * time.Second,
		ExpectContinueTimeout: 0,
		DisableCompression:    true,
		ForceAttemptHTTP2:     false,
	}
	client := &http.Client{
		Transport: transport,
		Timeout:   cfg.timeout,
	}

	// Quick pre-flight check to fail fast on auth/routing misconfiguration
	if err := preflightCheck(client, reqURL, headers, bodyBytes, cfg); err != nil {
		fmt.Fprintf(os.Stderr, "[FATAL] preflight check failed: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("==> Running [%s] target=%s protocol=%s model=%s stream=%v payload=%dB concurrency=%d warmup=%s duration=%s\n",
		cfg.label, reqURL, cfg.protocol, cfg.model, cfg.stream, len(bodyBytes), cfg.concurrency, cfg.warmup, cfg.duration)

	var recording atomic.Bool
	totalDuration := cfg.warmup + cfg.duration
	ctx, cancel := context.WithTimeout(context.Background(), totalDuration)
	defer cancel()

	var paceCh <-chan time.Time
	var ticker *time.Ticker
	if cfg.rate > 0 {
		interval := time.Duration(float64(time.Second) / cfg.rate)
		if interval < time.Microsecond {
			interval = time.Microsecond
		}
		ticker = time.NewTicker(interval)
		defer ticker.Stop()
		paceCh = ticker.C
	}

	results := make([]workerResult, cfg.concurrency)
	var wg sync.WaitGroup
	wg.Add(cfg.concurrency)

	var measureStart time.Time
	go func() {
		if cfg.warmup > 0 {
			t := time.NewTimer(cfg.warmup)
			select {
			case <-ctx.Done():
				t.Stop()
				return
			case <-t.C:
			}
		}
		measureStart = time.Now()
		recording.Store(true)
	}()

	for i := 0; i < cfg.concurrency; i++ {
		idx := i
		results[idx] = workerResult{
			statusCount: make(map[int]int64, 4),
		}
		go func() {
			defer wg.Done()
			runWorker(ctx, client, reqURL, headers, bodyBytes, cfg, &recording, paceCh, &results[idx])
		}()
	}

	wg.Wait()
	measureEnd := time.Now()
	if measureStart.IsZero() {
		measureStart = measureEnd.Add(-cfg.duration)
	}
	actualSec := measureEnd.Sub(measureStart).Seconds()
	if actualSec <= 0 {
		actualSec = cfg.duration.Seconds()
	}

	report := aggregate(cfg, len(bodyBytes), actualSec, results)
	printReport(report)
	if cfg.jsonOut != "" {
		raw, _ := json.MarshalIndent(report, "", "  ")
		if err := os.WriteFile(cfg.jsonOut, raw, 0o644); err != nil {
			fmt.Fprintf(os.Stderr, "[WARN] failed to write %s: %v\n", cfg.jsonOut, err)
		}
	}
}

func preflightCheck(client *http.Client, reqURL string, headers http.Header, body []byte, cfg config) error {
	req, err := http.NewRequest(http.MethodPost, reqURL, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header = headers.Clone()
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(snippet)))
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	return nil
}

func runWorker(
	ctx context.Context,
	client *http.Client,
	reqURL string,
	headers http.Header,
	body []byte,
	cfg config,
	recording *atomic.Bool,
	paceCh <-chan time.Time,
	res *workerResult,
) {
	reader := bufio.NewReaderSize(bytes.NewReader(nil), 16*1024)
	discardBuf := make([]byte, 16*1024)

	for {
		if ctx.Err() != nil {
			return
		}
		if paceCh != nil {
			select {
			case <-ctx.Done():
				return
			case <-paceCh:
			}
		}

		isRec := recording.Load()
		start := time.Now()

		reqCtx := ctx
		var reqCancel context.CancelFunc
		if cfg.abortAfterChunks > 0 {
			reqCtx, reqCancel = context.WithCancel(ctx)
		}

		req, err := http.NewRequestWithContext(reqCtx, http.MethodPost, reqURL, bytes.NewReader(body))
		if err != nil {
			if reqCancel != nil {
				reqCancel()
			}
			continue
		}
		req.Header = headers.Clone()

		resp, err := client.Do(req)
		if err != nil {
			if reqCancel != nil {
				reqCancel()
			}
			if ctx.Err() != nil {
				return
			}
			if isRec {
				res.errCount++
				if res.sampleErr == "" {
					res.sampleErr = err.Error()
				}
			}
			continue
		}

		status := resp.StatusCode
		var events int64
		var readBytes int64
		var ttft time.Duration
		abortedIntentionally := false

		if status >= 200 && status < 300 {
			if cfg.stream {
				reader.Reset(resp.Body)
				for {
					line, rerr := reader.ReadSlice('\n')
					readBytes += int64(len(line))
					if len(line) > 5 && bytes.HasPrefix(line, []byte("data:")) {
						trimmed := bytes.TrimSpace(line[5:])
						if len(trimmed) > 0 && !bytes.Equal(trimmed, []byte("[DONE]")) {
							if events == 0 {
								ttft = time.Since(start)
							}
							events++
							if cfg.slowReadMs > 0 {
								time.Sleep(time.Duration(cfg.slowReadMs) * time.Millisecond)
							}
							if cfg.abortAfterChunks > 0 && int(events) >= cfg.abortAfterChunks {
								abortedIntentionally = true
								break
							}
						}
					}
					if rerr != nil {
						if rerr != io.EOF && rerr != bufio.ErrBufferFull && ctx.Err() == nil {
							err = rerr
						}
						if rerr == bufio.ErrBufferFull {
							continue
						}
						break
					}
				}
			} else {
				for {
					n, rerr := resp.Body.Read(discardBuf)
					readBytes += int64(n)
					if rerr != nil {
						if rerr != io.EOF && ctx.Err() == nil {
							err = rerr
						}
						break
					}
				}
			}
		} else {
			snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
			readBytes += int64(len(snippet))
			_, _ = io.Copy(io.Discard, resp.Body)
			if isRec && res.sampleErr == "" {
				res.sampleErr = fmt.Sprintf("HTTP %d: %s", status, strings.TrimSpace(string(snippet)))
			}
		}

		_ = resp.Body.Close()
		if reqCancel != nil {
			reqCancel()
		}
		elapsed := time.Since(start)

		if !isRec {
			continue
		}
		res.statusCount[status]++
		res.bytesRead += readBytes
		res.events += events

		if err != nil && !abortedIntentionally {
			res.errCount++
			if res.sampleErr == "" {
				res.sampleErr = err.Error()
			}
			continue
		}
		if status >= 200 && status < 300 {
			res.latenciesNs = append(res.latenciesNs, elapsed.Nanoseconds())
			if cfg.stream && ttft > 0 {
				res.ttftNs = append(res.ttftNs, ttft.Nanoseconds())
				if events > 1 && elapsed > ttft {
					perChunk := (elapsed - ttft).Nanoseconds() / (events - 1)
					res.tpotNs = append(res.tpotNs, perChunk)
				}
			}
		} else {
			res.errCount++
		}
	}
}

func aggregate(cfg config, payloadBytes int, sec float64, results []workerResult) summaryReport {
	statusCounts := make(map[int]int64)
	var allLat, allTTFT, allTPOT []int64
	var totalReq, okReq, errReq, totalEvents, totalBytes int64
	var sampleErr string

	for _, r := range results {
		allLat = append(allLat, r.latenciesNs...)
		allTTFT = append(allTTFT, r.ttftNs...)
		allTPOT = append(allTPOT, r.tpotNs...)
		errReq += r.errCount
		totalEvents += r.events
		totalBytes += r.bytesRead
		if sampleErr == "" && r.sampleErr != "" {
			sampleErr = r.sampleErr
		}
		for code, cnt := range r.statusCount {
			statusCounts[code] += cnt
			totalReq += cnt
			if code >= 200 && code < 300 {
				okReq += cnt
			}
		}
	}
	if totalReq < okReq+errReq {
		totalReq = okReq + errReq
	}

	rep := summaryReport{
		Label:           cfg.label,
		Protocol:        cfg.protocol,
		Model:           cfg.model,
		Stream:          cfg.stream,
		PayloadBytes:    payloadBytes,
		Concurrency:     cfg.concurrency,
		DurationSec:     round2(sec),
		TotalRequests:   totalReq,
		SuccessRequests: okReq,
		ErrorRequests:   errReq,
		StatusCounts:    statusCounts,
		SampleError:     sampleErr,
		RPS:             round2(float64(okReq) / sec),
		TotalEvents:     totalEvents,
		ReadMBPerSec:    round2(float64(totalBytes) / (1024 * 1024) / sec),
		LatencyMs:       computePercentiles(allLat),
	}
	if cfg.stream && len(allTTFT) > 0 {
		rep.EventsPerSec = round2(float64(totalEvents) / sec)
		p := computePercentiles(allTTFT)
		rep.TTFTMs = &p
	}
	if cfg.stream && len(allTPOT) > 0 {
		p := computePercentiles(allTPOT)
		rep.TPOTMs = &p
	}
	return rep
}

func computePercentiles(ns []int64) percentiles {
	if len(ns) == 0 {
		return percentiles{}
	}
	sort.Slice(ns, func(i, j int) bool { return ns[i] < ns[j] })
	var sum float64
	for _, v := range ns {
		sum += float64(v)
	}
	toMs := func(v int64) float64 { return round2(float64(v) / 1e6) }
	at := func(q float64) float64 {
		idx := int(math.Ceil(q*float64(len(ns)))) - 1
		if idx < 0 {
			idx = 0
		}
		if idx >= len(ns) {
			idx = len(ns) - 1
		}
		return toMs(ns[idx])
	}
	return percentiles{
		Min:   toMs(ns[0]),
		Mean:  round2((sum / float64(len(ns))) / 1e6),
		P50:   at(0.50),
		P90:   at(0.90),
		P95:   at(0.95),
		P99:   at(0.99),
		P999:  at(0.999),
		Max:   toMs(ns[len(ns)-1]),
		Count: len(ns),
	}
}

func round2(v float64) float64 {
	return math.Round(v*100) / 100
}

func printReport(r summaryReport) {
	fmt.Printf("---- Result [%s] ----\n", r.Label)
	fmt.Printf("  Requests: total=%d ok=%d err=%d statuses=%v (%.2fs)\n",
		r.TotalRequests, r.SuccessRequests, r.ErrorRequests, r.StatusCounts, r.DurationSec)
	fmt.Printf("  Throughput: RPS=%.2f  Read=%.2f MB/s", r.RPS, r.ReadMBPerSec)
	if r.Stream {
		fmt.Printf("  SSE_Events/s=%.2f (total=%d)", r.EventsPerSec, r.TotalEvents)
	}
	fmt.Println()
	fmt.Printf("  Total Latency (ms): min=%.2f mean=%.2f p50=%.2f p90=%.2f p95=%.2f p99=%.2f p99.9=%.2f max=%.2f\n",
		r.LatencyMs.Min, r.LatencyMs.Mean, r.LatencyMs.P50, r.LatencyMs.P90, r.LatencyMs.P95, r.LatencyMs.P99, r.LatencyMs.P999, r.LatencyMs.Max)
	if r.TTFTMs != nil {
		fmt.Printf("  TTFT (ms):          min=%.2f mean=%.2f p50=%.2f p90=%.2f p95=%.2f p99=%.2f p99.9=%.2f max=%.2f\n",
			r.TTFTMs.Min, r.TTFTMs.Mean, r.TTFTMs.P50, r.TTFTMs.P90, r.TTFTMs.P95, r.TTFTMs.P99, r.TTFTMs.P999, r.TTFTMs.Max)
	}
	if r.TPOTMs != nil {
		fmt.Printf("  TPOT (ms/chunk):    min=%.2f mean=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f\n",
			r.TPOTMs.Min, r.TPOTMs.Mean, r.TPOTMs.P50, r.TPOTMs.P95, r.TPOTMs.P99, r.TPOTMs.Max)
	}
	if r.SampleError != "" {
		fmt.Printf("  Sample Error: %s\n", r.SampleError)
	}
}

func buildRequestSpec(cfg config) (string, http.Header, []byte) {
	base := strings.TrimRight(cfg.target, "/")
	headers := make(http.Header)
	headers.Set("Content-Type", "application/json")

	toolCount := cfg.tools
	if toolCount == 0 && cfg.payloadKB >= 10 {
		toolCount = 16
	}

	targetBytes := cfg.payloadKB * 1024
	paddingBlock := strings.Repeat("The gateway routes multimodal agent requests with deterministic micro-USD billing and non-blocking I/O. ", 10)

	switch cfg.protocol {
	case "openai-chat":
		headers.Set("Authorization", "Bearer "+cfg.apiKey)
		msgs := []map[string]any{
			{"role": "system", "content": "You are a concise coding agent."},
			{"role": "user", "content": "Summarize the benchmark payload and confirm readiness."},
		}
		var tools []map[string]any
		for i := 0; i < toolCount; i++ {
			tools = append(tools, map[string]any{
				"type": "function",
				"function": map[string]any{
					"name":        fmt.Sprintf("tool_op_%02d", i),
					"description": fmt.Sprintf("Executes benchmark agent operation %d on workspace files.", i),
					"parameters": map[string]any{
						"type": "object",
						"properties": map[string]any{
							"path":    map[string]any{"type": "string", "description": "Target file path"},
							"pattern": map[string]any{"type": "string", "description": "Regex search pattern"},
							"limit":   map[string]any{"type": "integer", "description": "Maximum matches"},
						},
						"required": []string{"path"},
					},
				},
			})
		}
		bodyMap := map[string]any{
			"model":      cfg.model,
			"stream":     cfg.stream,
			"max_tokens": 256,
			"messages":   msgs,
		}
		if len(tools) > 0 {
			bodyMap["tools"] = tools
		}
		raw, _ := json.Marshal(bodyMap)
		for len(raw) < targetBytes {
			msgs = append(msgs,
				map[string]any{"role": "assistant", "content": "Acknowledged context chunk: " + paddingBlock},
				map[string]any{"role": "user", "content": "Next file excerpt for analysis: " + paddingBlock},
			)
			bodyMap["messages"] = msgs
			raw, _ = json.Marshal(bodyMap)
		}
		return base + "/v1/chat/completions", headers, raw

	case "openai-responses":
		headers.Set("Authorization", "Bearer "+cfg.apiKey)
		input := []map[string]any{
			{"role": "user", "content": "Summarize the benchmark payload and confirm readiness."},
		}
		var tools []map[string]any
		for i := 0; i < toolCount; i++ {
			tools = append(tools, map[string]any{
				"type":        "function",
				"name":        fmt.Sprintf("tool_op_%02d", i),
				"description": fmt.Sprintf("Executes benchmark agent operation %d on workspace files.", i),
				"parameters": map[string]any{
					"type": "object",
					"properties": map[string]any{
						"path":  map[string]any{"type": "string"},
						"limit": map[string]any{"type": "integer"},
					},
					"required": []string{"path"},
				},
			})
		}
		bodyMap := map[string]any{
			"model":             cfg.model,
			"stream":            cfg.stream,
			"instructions":      "You are a concise coding agent.",
			"max_output_tokens": 256,
			"input":             input,
		}
		if len(tools) > 0 {
			bodyMap["tools"] = tools
		}
		raw, _ := json.Marshal(bodyMap)
		for len(raw) < targetBytes {
			input = append(input,
				map[string]any{"role": "assistant", "content": "Acknowledged context chunk: " + paddingBlock},
				map[string]any{"role": "user", "content": "Next file excerpt for analysis: " + paddingBlock},
			)
			bodyMap["input"] = input
			raw, _ = json.Marshal(bodyMap)
		}
		return base + "/v1/responses", headers, raw

	case "anthropic":
		headers.Set("x-api-key", cfg.apiKey)
		headers.Set("anthropic-version", "2023-06-01")
		msgs := []map[string]any{
			{"role": "user", "content": "Summarize the benchmark payload and confirm readiness."},
		}
		var tools []map[string]any
		for i := 0; i < toolCount; i++ {
			tools = append(tools, map[string]any{
				"name":        fmt.Sprintf("tool_op_%02d", i),
				"description": fmt.Sprintf("Executes benchmark agent operation %d on workspace files.", i),
				"input_schema": map[string]any{
					"type": "object",
					"properties": map[string]any{
						"path":    map[string]any{"type": "string"},
						"pattern": map[string]any{"type": "string"},
						"limit":   map[string]any{"type": "integer"},
					},
					"required": []string{"path"},
				},
			})
		}
		bodyMap := map[string]any{
			"model":      cfg.model,
			"stream":     cfg.stream,
			"max_tokens": 256,
			"system":     "You are a concise coding agent.",
			"messages":   msgs,
		}
		if len(tools) > 0 {
			bodyMap["tools"] = tools
		}
		raw, _ := json.Marshal(bodyMap)
		for len(raw) < targetBytes {
			msgs = append(msgs,
				map[string]any{"role": "assistant", "content": "Acknowledged context chunk: " + paddingBlock},
				map[string]any{"role": "user", "content": "Next file excerpt for analysis: " + paddingBlock},
			)
			bodyMap["messages"] = msgs
			raw, _ = json.Marshal(bodyMap)
		}
		return base + "/v1/messages", headers, raw

	case "gemini":
		headers.Set("x-goog-api-key", cfg.apiKey)
		contents := []map[string]any{
			{"role": "user", "parts": []map[string]any{{"text": "Summarize the benchmark payload and confirm readiness."}}},
		}
		bodyMap := map[string]any{
			"systemInstruction": map[string]any{
				"parts": []map[string]any{{"text": "You are a concise coding agent."}},
			},
			"contents": contents,
			"generationConfig": map[string]any{
				"maxOutputTokens": 256,
			},
		}
		if toolCount > 0 {
			var decls []map[string]any
			for i := 0; i < toolCount; i++ {
				decls = append(decls, map[string]any{
					"name":        fmt.Sprintf("tool_op_%02d", i),
					"description": fmt.Sprintf("Executes benchmark agent operation %d.", i),
					"parametersJsonSchema": map[string]any{
						"type": "object",
						"properties": map[string]any{
							"path": map[string]any{"type": "string"},
						},
					},
				})
			}
			bodyMap["tools"] = []map[string]any{{"functionDeclarations": decls}}
		}
		raw, _ := json.Marshal(bodyMap)
		for len(raw) < targetBytes {
			contents = append(contents,
				map[string]any{"role": "model", "parts": []map[string]any{{"text": "Acknowledged context chunk: " + paddingBlock}}},
				map[string]any{"role": "user", "parts": []map[string]any{{"text": "Next file excerpt for analysis: " + paddingBlock}}},
			)
			bodyMap["contents"] = contents
			raw, _ = json.Marshal(bodyMap)
		}
		action := ":generateContent"
		if cfg.stream {
			action = ":streamGenerateContent?alt=sse"
		}
		return base + "/v1beta/models/" + cfg.model + action, headers, raw

	default:
		fmt.Fprintf(os.Stderr, "unsupported protocol: %s\n", cfg.protocol)
		os.Exit(2)
		return "", nil, nil
	}
}
