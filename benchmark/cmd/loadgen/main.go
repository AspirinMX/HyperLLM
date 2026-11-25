// Command loadgen drives one benchmark scenario against the gateway.
//
// Every worker runs a closed loop: send one request, consume the whole response, send the next. Workers
// are launched evenly across -ramp so the target concurrency is reached gradually instead of dumping N
// connections on the gateway in the same instant; the reported metrics cover only the -duration window
// that starts once the ramp is complete, during which every worker is already inside its stream. That
// is what keeps "N concurrent SSE streams" true for the whole window even when one stream lasts 10s.
package main

import (
	"bufio"
	"bytes"
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
	target        string
	protocol      string // chat | anthropic (downstream protocol; upstream is always OpenAI Chat)
	model         string
	apiKey        string
	stream        bool
	payloadTokens int
	maxTokens     int
	concurrency   int
	ramp          time.Duration
	duration      time.Duration
	timeout       time.Duration
	tokensPerFrame int
	jsonOut       string
	label         string
}

type percentiles struct {
	Min  float64 `json:"min"`
	Mean float64 `json:"mean"`
	P50  float64 `json:"p50"`
	P90  float64 `json:"p90"`
	P95  float64 `json:"p95"`
	P99  float64 `json:"p99"`
	Max  float64 `json:"max"`
}

type floatPercentiles struct {
	Min  float64 `json:"min"`
	Mean float64 `json:"mean"`
	P50  float64 `json:"p50"`
	P90  float64 `json:"p90"`
	P95  float64 `json:"p95"`
	P99  float64 `json:"p99"`
	Max  float64 `json:"max"`
}

type inflightStats struct {
	Min  int64   `json:"min"`
	Avg  float64 `json:"avg"`
	P95  int64   `json:"p95"`
	Max  int64   `json:"max"`
	Target int64 `json:"target"`
	MaintainedPct float64 `json:"maintained_pct"`
}

type report struct {
	Label            string         `json:"label"`
	Protocol         string         `json:"protocol"`
	Stream           bool           `json:"stream"`
	Concurrency      int            `json:"concurrency"`
	RampSec          float64        `json:"ramp_sec"`
	DurationSec      float64        `json:"duration_sec"`
	PayloadBytes     int            `json:"payload_bytes"`
	PayloadTokens    int            `json:"payload_tokens"`
	Requests         int64          `json:"requests"`
	OK               int64          `json:"ok"`
	Errors           int64          `json:"errors"`
	StatusCounts     map[int]int64  `json:"status_counts"`
	SampleError      string         `json:"sample_error,omitempty"`
	RPS              float64        `json:"rps"`
	EventsPerSec     float64        `json:"events_per_sec,omitempty"`
	AggregateTPS     float64        `json:"aggregate_output_tps,omitempty"`
	InboundMBPerSec  float64        `json:"inbound_mb_per_sec"`
	ReadMBPerSec     float64        `json:"read_mb_per_sec"`
	LatencyMs        percentiles    `json:"latency_ms"`
	TTFTMs           *percentiles   `json:"ttft_ms,omitempty"`
	TPOTMs           *percentiles   `json:"tpot_ms,omitempty"`
	StreamTPS        *floatPercentiles `json:"stream_tps,omitempty"`
	Inflight         inflightStats  `json:"inflight"`
}

type workerResult struct {
	mu          sync.Mutex
	latencies   []int64
	ttfts       []int64
	tpots       []int64
	streamTPS   []float64
	statuses    map[int]int64
	errors      int64
	sampleError string
	events      int64
	bytesRead   int64
}

func (r *workerResult) record(fn func()) {
	r.mu.Lock()
	fn()
	r.mu.Unlock()
}

func main() {
	cfg := config{}
	flag.StringVar(&cfg.target, "target", "http://127.0.0.1:8080", "Gateway base URL")
	flag.StringVar(&cfg.protocol, "protocol", "chat", "Downstream protocol: chat | anthropic")
	flag.StringVar(&cfg.model, "model", "bench-openai-chat", "Virtual model code")
	flag.StringVar(&cfg.apiKey, "api-key", "sk-bench0010000000000000000000000000000000000000001", "Gateway API key")
	flag.BoolVar(&cfg.stream, "stream", true, "Request streaming (SSE) responses")
	flag.IntVar(&cfg.payloadTokens, "payload-tokens", 100000, "Approximate prompt size in tokens (4 bytes/token)")
	flag.IntVar(&cfg.maxTokens, "max-tokens", 2000, "max_tokens sent in the request")
	flag.IntVar(&cfg.concurrency, "concurrency", 300, "Concurrent in-flight requests held during the measured window")
	flag.DurationVar(&cfg.ramp, "ramp", 20*time.Second, "Spread worker starts evenly over this window")
	flag.DurationVar(&cfg.duration, "duration", 60*time.Second, "Measured steady-state window (after the ramp)")
	flag.DurationVar(&cfg.timeout, "timeout", 300*time.Second, "Per-request timeout")
	flag.IntVar(&cfg.tokensPerFrame, "tokens-per-frame", 5, "Output tokens per SSE frame (for TPS reporting)")
	flag.StringVar(&cfg.jsonOut, "json-out", "", "Optional path for the JSON report")
	flag.StringVar(&cfg.label, "label", "", "Scenario label")
	flag.Parse()

	if cfg.label == "" {
		cfg.label = fmt.Sprintf("%s_%s_c%d", cfg.protocol, mode(cfg.stream), cfg.concurrency)
	}

	url, headers, body := buildRequest(cfg)

	client := &http.Client{
		Timeout: cfg.timeout,
		Transport: &http.Transport{
			Proxy:                 nil,
			DialContext:           (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
			MaxIdleConns:          cfg.concurrency * 2,
			MaxIdleConnsPerHost:   cfg.concurrency * 2,
			MaxConnsPerHost:       0,
			IdleConnTimeout:       90 * time.Second,
			DisableCompression:    true,
			ResponseHeaderTimeout: cfg.timeout,
		},
	}

	fmt.Printf("==> %s: %s %s concurrency=%d ramp=%s duration=%s payload=%dB (~%d tokens) stream=%v\n",
		cfg.label, cfg.protocol, url, cfg.concurrency, cfg.ramp, cfg.duration, len(body), cfg.payloadTokens, cfg.stream)

	var inflight atomic.Int64
	results := make([]workerResult, cfg.concurrency)
	for i := range results {
		results[i].statuses = make(map[int]int64, 4)
	}

	runStart := time.Now()
	measureStart := runStart.Add(cfg.ramp)
	runEnd := measureStart.Add(cfg.duration)

	done := make(chan struct{})
	var inflightSamplesMu sync.Mutex
	var inflightSamples []int64
	go func() {
		ticker := time.NewTicker(100 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case now := <-ticker.C:
				if !now.Before(measureStart) && !now.After(runEnd) {
					inflightSamplesMu.Lock()
					inflightSamples = append(inflightSamples, inflight.Load())
					inflightSamplesMu.Unlock()
				}
			}
		}
	}()

	var wg sync.WaitGroup
	for i := 0; i < cfg.concurrency; i++ {
		startAt := runStart
		if cfg.concurrency > 1 {
			startAt = runStart.Add(time.Duration(float64(cfg.ramp) * float64(i) / float64(cfg.concurrency)))
		}
		wg.Add(1)
		go func(idx int, startAt time.Time) {
			defer wg.Done()
			if d := time.Until(startAt); d > 0 {
				time.Sleep(d)
			}
			runWorker(client, url, headers, body, cfg, measureStart, runEnd, &inflight, &results[idx])
		}(i, startAt)
	}
	wg.Wait()
	close(done)
	// The measured window is exactly -duration. Requests that started inside it are still finishing
	// when the last worker exits; they are counted, and the denominator stays the window itself so RPS
	// is an arrival rate rather than a completion rate. Wall time is therefore duration + one stream.
	elapsed := cfg.duration

	rep := aggregate(cfg, len(body), elapsed.Seconds(), results, inflightSamples)
	if rep.Requests == 0 {
		fmt.Println("WARNING: no request was started inside the measured window; raise -duration " +
			"(the window must be longer than one stream, ~10s at the defaults) or lower -ramp")
	}
	printReport(rep)
	if cfg.jsonOut != "" {
		if err := writeJSON(cfg.jsonOut, rep); err != nil {
			fmt.Fprintf(os.Stderr, "write %s: %v\n", cfg.jsonOut, err)
		}
	}
}

func mode(stream bool) string {
	if stream {
		return "stream"
	}
	return "nonstream"
}

func buildRequest(cfg config) (string, http.Header, []byte) {
	base := strings.TrimRight(cfg.target, "/")
	headers := http.Header{}
	headers.Set("Content-Type", "application/json")

	targetBytes := cfg.payloadTokens * 4
	padding := strings.Repeat("The gateway routes multimodal agent requests with deterministic micro-USD billing and non-blocking I/O. ", 10)

	if cfg.protocol == "anthropic" {
		headers.Set("x-api-key", cfg.apiKey)
		headers.Set("anthropic-version", "2023-06-01")
		msgs := []map[string]any{{"role": "user", "content": "Summarize the benchmark payload."}}
		body := map[string]any{
			"model":      cfg.model,
			"stream":     cfg.stream,
			"max_tokens": cfg.maxTokens,
			"system":     "You are a concise coding agent.",
			"messages":   msgs,
		}
		raw, _ := json.Marshal(body)
		for len(raw) < targetBytes {
			msgs = append(msgs,
				map[string]any{"role": "assistant", "content": "Acknowledged context chunk: " + padding},
				map[string]any{"role": "user", "content": "Next file excerpt for analysis: " + padding})
			body["messages"] = msgs
			raw, _ = json.Marshal(body)
		}
		return base + "/v1/messages", headers, raw
	}

	headers.Set("Authorization", "Bearer "+cfg.apiKey)
	msgs := []map[string]any{
		{"role": "system", "content": "You are a concise coding agent."},
		{"role": "user", "content": "Summarize the benchmark payload."},
	}
	body := map[string]any{
		"model":      cfg.model,
		"stream":     cfg.stream,
		"max_tokens": cfg.maxTokens,
		"messages":   msgs,
	}
	raw, _ := json.Marshal(body)
	for len(raw) < targetBytes {
		msgs = append(msgs,
			map[string]any{"role": "assistant", "content": "Acknowledged context chunk: " + padding},
			map[string]any{"role": "user", "content": "Next file excerpt for analysis: " + padding})
		body["messages"] = msgs
		raw, _ = json.Marshal(body)
	}
	return base + "/v1/chat/completions", headers, raw
}

func runWorker(client *http.Client, url string, headers http.Header, body []byte, cfg config,
	measureStart, runEnd time.Time, inflight *atomic.Int64, res *workerResult) {
	reader := bytes.NewReader(body)
	for {
		now := time.Now()
		if !now.Before(runEnd) {
			return
		}
		reader.Reset(body)
		req, err := http.NewRequest(http.MethodPost, url, reader)
		if err != nil {
			res.record(func() { res.errors++ ; res.sampleError = err.Error() })
			return
		}
		req.Header = headers.Clone()
		recording := !now.Before(measureStart)

		inflight.Add(1)
		start := time.Now()
		resp, err := client.Do(req)
		if err != nil {
			inflight.Add(-1)
			if recording {
				res.record(func() { res.errors++; if res.sampleError == "" { res.sampleError = err.Error() } })
			}
			continue
		}
		status := resp.StatusCode
		var ttft time.Duration
		var frames int64
		var readBytes int64
		if resp.StatusCode/100 == 2 && cfg.stream {
			ttft, frames, readBytes = readSSE(resp.Body, start)
		} else {
			first := true
			buf := make([]byte, 32<<10)
			for {
				n, rerr := resp.Body.Read(buf)
				if n > 0 {
					if first {
						ttft = time.Since(start)
						first = false
					}
					readBytes += int64(n)
				}
				if rerr != nil {
					break
				}
			}
		}
		resp.Body.Close()
		inflight.Add(-1)
		elapsed := time.Since(start)

		if !recording {
			continue
		}
		res.record(func() {
			res.statuses[status]++
			res.latencies = append(res.latencies, elapsed.Nanoseconds())
			res.events += frames
			res.bytesRead += readBytes
			if status/100 != 2 {
				res.errors++
				if res.sampleError == "" {
					res.sampleError = fmt.Sprintf("HTTP %d", status)
				}
				return
			}
			if cfg.stream && frames > 0 {
				res.ttfts = append(res.ttfts, ttft.Nanoseconds())
				decode := elapsed - ttft
				if frames > 1 && decode > 0 {
					res.tpots = append(res.tpots, (decode.Nanoseconds() / (frames - 1)))
					res.streamTPS = append(res.streamTPS,
						float64(frames-1)*float64(cfg.tokensPerFrame)/decode.Seconds())
				}
			}
		})
	}
}

// readSSE counts data frames and returns time-to-first-frame, frame count and body bytes.
func readSSE(body io.Reader, start time.Time) (time.Duration, int64, int64) {
	br := bufio.NewReaderSize(body, 64<<10)
	var frames int64
	var ttft time.Duration
	var readBytes int64
	for {
		line, err := br.ReadSlice('\n')
		if len(line) > 0 {
			readBytes += int64(len(line))
			if ttft == 0 && bytes.HasPrefix(line, []byte("data:")) {
				ttft = time.Since(start)
			}
			if bytes.HasPrefix(line, []byte("data:")) {
				payload := bytes.TrimSpace(line[5:])
				// Only content deltas count as output tokens: the terminal finish_reason and usage
				// frames carry no generated text and must not inflate the frame count.
				if len(payload) > 0 && !bytes.Equal(payload, []byte("[DONE]")) && bytes.Contains(payload, []byte("\"content\":")) {
					frames++
				}
			}
		}
		if err != nil {
			break
		}
	}
	return ttft, frames, readBytes
}

func aggregate(cfg config, payloadBytes int, sec float64, results []workerResult, inflight []int64) report {
	statuses := map[int]int64{}
	var lat, ttft, tpot []int64
	var tps []float64
	var totalReq, okReq, errReq, events, readBytes int64
	var sampleErr string
	for i := range results {
		r := &results[i]
		r.mu.Lock()
		lat = append(lat, r.latencies...)
		ttft = append(ttft, r.ttfts...)
		tpot = append(tpot, r.tpots...)
		tps = append(tps, r.streamTPS...)
		for k, v := range r.statuses {
			statuses[k] += v
		}
		errReq += r.errors
		events += r.events
		readBytes += r.bytesRead
		if sampleErr == "" {
			sampleErr = r.sampleError
		}
		r.mu.Unlock()
	}
	totalReq = int64(len(lat))
	okReq = totalReq - errReq
	rep := report{
		Label: cfg.label, Protocol: cfg.protocol, Stream: cfg.stream,
		Concurrency: cfg.concurrency, RampSec: cfg.ramp.Seconds(), DurationSec: round2(sec),
		PayloadBytes: payloadBytes, PayloadTokens: cfg.payloadTokens,
		Requests: totalReq, OK: okReq, Errors: errReq, StatusCounts: statuses,
		SampleError: sampleErr,
		RPS: round2(float64(okReq) / sec),
		InboundMBPerSec: round2(float64(okReq*int64(payloadBytes)) / (1024 * 1024) / sec),
		ReadMBPerSec: round2(float64(readBytes) / (1024 * 1024) / sec),
		LatencyMs: computePercentiles(lat),
		Inflight: computeInflight(inflight, int64(cfg.concurrency)),
	}
	if cfg.stream && len(ttft) > 0 {
		rep.EventsPerSec = round2(float64(events) / sec)
		rep.AggregateTPS = round2(rep.EventsPerSec * float64(cfg.tokensPerFrame))
		p := computePercentiles(ttft)
		rep.TTFTMs = &p
	}
	if len(tpot) > 0 {
		p := computePercentiles(tpot)
		rep.TPOTMs = &p
	}
	if len(tps) > 0 {
		p := computeFloatPercentiles(tps)
		rep.StreamTPS = &p
	}
	return rep
}

func computeFloatPercentiles(vals []float64) floatPercentiles {
	if len(vals) == 0 {
		return floatPercentiles{}
	}
	sorted := append([]float64(nil), vals...)
	sort.Float64s(sorted)
	var sum float64
	for _, v := range sorted {
		sum += v
	}
	at := func(q float64) float64 {
		idx := int(math.Ceil(q*float64(len(sorted)))) - 1
		if idx < 0 {
			idx = 0
		}
		if idx >= len(sorted) {
			idx = len(sorted) - 1
		}
		return round2(sorted[idx])
	}
	return floatPercentiles{
		Min: round2(sorted[0]), Mean: round2(sum / float64(len(sorted))),
		P50: at(0.50), P90: at(0.90), P95: at(0.95), P99: at(0.99), Max: round2(sorted[len(sorted)-1]),
	}
}

func computePercentiles(ns []int64) percentiles {
	if len(ns) == 0 {
		return percentiles{}
	}
	sort.Slice(ns, func(i, j int) bool { return ns[i] < ns[j] })
	var sum int64
	for _, v := range ns {
		sum += v
	}
	at := func(q float64) float64 {
		idx := int(math.Ceil(q*float64(len(ns)))) - 1
		if idx < 0 {
			idx = 0
		}
		if idx >= len(ns) {
			idx = len(ns) - 1
		}
		return round2(float64(ns[idx]) / 1e6)
	}
	return percentiles{
		Min: round2(float64(ns[0]) / 1e6), Mean: round2(float64(sum) / float64(len(ns)) / 1e6),
		P50: at(0.50), P90: at(0.90), P95: at(0.95), P99: at(0.99),
		Max: round2(float64(ns[len(ns)-1]) / 1e6),
	}
}

func computeInflight(samples []int64, target int64) inflightStats {
	if len(samples) == 0 {
		return inflightStats{Target: target}
	}
	sorted := append([]int64(nil), samples...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
	var sum int64
	for _, v := range sorted {
		sum += v
	}
	p95 := sorted[int(math.Ceil(0.95*float64(len(sorted))))-1]
	maintained := 0
	for _, v := range sorted {
		if v >= target*95/100 {
			maintained++
		}
	}
	return inflightStats{
		Min: sorted[0], Avg: round2(float64(sum) / float64(len(sorted))), P95: p95, Max: sorted[len(sorted)-1],
		Target: target, MaintainedPct: round2(100 * float64(maintained) / float64(len(sorted))),
	}
}

func printReport(r report) {
	fmt.Printf("---- Result ----\n")
	fmt.Printf("  Requests: ok=%d err=%d statuses=%v (%.2fs)\n", r.OK, r.Errors, r.StatusCounts, r.DurationSec)
	fmt.Printf("  Throughput: RPS=%.2f Inbound=%.2f MB/s Read=%.2f MB/s", r.RPS, r.InboundMBPerSec, r.ReadMBPerSec)
	if r.Stream {
		fmt.Printf("  SSE=%.0f frames/s  OutTPS=%.0f", r.EventsPerSec, r.AggregateTPS)
	}
	fmt.Println()
	fmt.Printf("  Latency (ms): min=%.1f mean=%.1f p50=%.1f p90=%.1f p95=%.1f p99=%.1f max=%.1f\n",
		r.LatencyMs.Min, r.LatencyMs.Mean, r.LatencyMs.P50, r.LatencyMs.P90, r.LatencyMs.P95, r.LatencyMs.P99, r.LatencyMs.Max)
	if r.TTFTMs != nil {
		fmt.Printf("  TTFT (ms):    min=%.1f mean=%.1f p50=%.1f p90=%.1f p95=%.1f p99=%.1f max=%.1f\n",
			r.TTFTMs.Min, r.TTFTMs.Mean, r.TTFTMs.P50, r.TTFTMs.P90, r.TTFTMs.P95, r.TTFTMs.P99, r.TTFTMs.Max)
	}
	if r.TPOTMs != nil {
		fmt.Printf("  TPOT (ms):    p50=%.2f p95=%.2f p99=%.2f\n", r.TPOTMs.P50, r.TPOTMs.P95, r.TPOTMs.P99)
	}
	if r.StreamTPS != nil {
		fmt.Printf("  StreamTPS:    min=%.1f p50=%.1f p95=%.1f p99=%.1f tok/s\n",
			r.StreamTPS.Min, r.StreamTPS.P50, r.StreamTPS.P95, r.StreamTPS.P99)
	}
	fmt.Printf("  In-flight:    min=%d avg=%.1f p95=%d max=%d target=%d maintained=%.1f%%\n",
		r.Inflight.Min, r.Inflight.Avg, r.Inflight.P95, r.Inflight.Max, r.Inflight.Target, r.Inflight.MaintainedPct)
	if r.SampleError != "" {
		fmt.Printf("  Sample error: %s\n", r.SampleError)
	}
}

func writeJSON(path string, rep report) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	enc := json.NewEncoder(f)
	enc.SetIndent("", "  ")
	return enc.Encode(rep)
}

func round2(v float64) float64 { return math.Round(v*100) / 100 }
