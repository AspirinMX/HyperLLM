package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.protocol.codec.DownstreamStreamEncoder;
import app.pagefault.hyperllm.protocol.codec.StreamEventSink;
import app.pagefault.hyperllm.protocol.codec.UpstreamStreamDecoder;
import app.pagefault.hyperllm.protocol.io.ByteSink;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalStreamEvent;
import app.pagefault.hyperllm.protocol.ir.StreamRequestMetadata;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;

import java.util.List;

/**
 * Push-based zero-allocation streaming bridge composing {@link UpstreamStreamDecoder} &rarr;
 * incremental token counter / usage sniffing &rarr; {@link DownstreamStreamEncoder} directly into
 * a caller-supplied {@link ByteSink}.
 *
 * <p>Retains only lightweight {@link StreamRequestMetadata} (~48 bytes) and a 24-byte
 * {@link TokenEstimator.TextTokenCounter} so large requests and long completions never accumulate
 * in Old Gen across long-lived SSE streams.
 */
public final class TranslatingStreamBridge implements StreamBridge, StreamEventSink {

    private static final byte[] EMPTY_FRAME = new byte[0];

    private final StreamRequestMetadata inboundMeta;
    private final UpstreamStreamDecoder decoder;
    private final DownstreamStreamEncoder encoder;
    private final TokenEstimator.TextTokenCounter completionCounter = new TokenEstimator.TextTokenCounter();
    private ByteSink.HeapByteSink reusableHeapSink;
    private ByteSink activeOut;
    private TokenUsage observedUsage;
    private boolean sawUsage;

    public TranslatingStreamBridge(
            StreamRequestMetadata inboundMeta,
            UpstreamStreamDecoder decoder,
            DownstreamStreamEncoder encoder,
            TokenEstimator tokenEstimator
    ) {
        this.inboundMeta = inboundMeta;
        this.decoder = decoder;
        this.encoder = encoder;
    }

    public TranslatingStreamBridge(
            CanonicalRequest inboundRequest,
            UpstreamStreamDecoder decoder,
            DownstreamStreamEncoder encoder,
            TokenEstimator tokenEstimator
    ) {
        this(
                StreamRequestMetadata.fromCanonical(inboundRequest, tokenEstimator.estimatePromptTokens(inboundRequest)),
                decoder,
                encoder,
                tokenEstimator
        );
    }

    @Override
    public String contentType() {
        return encoder.contentType();
    }

    @Override
    public void feed(byte[] upstreamChunk, int offset, int length, ByteSink out) {
        if (upstreamChunk == null || length <= 0) {
            return;
        }
        this.activeOut = out;
        try {
            decoder.feed(upstreamChunk, offset, length, this);
        } finally {
            this.activeOut = null;
        }
    }

    @Override
    public void complete(ByteSink out) {
        this.activeOut = out;
        try {
            decoder.complete(this);
            encoder.finish(out);
        } finally {
            this.activeOut = null;
        }
    }

    @Override
    public byte[] feed(byte[] upstreamChunk) {
        if (upstreamChunk == null || upstreamChunk.length == 0) {
            return EMPTY_FRAME;
        }
        ByteSink.HeapByteSink sink = heapSink();
        sink.reset();
        feed(upstreamChunk, 0, upstreamChunk.length, sink);
        return sink.writtenBytes() == 0 ? EMPTY_FRAME : sink.toCopiedByteArray();
    }

    @Override
    public List<byte[]> complete() {
        ByteSink.HeapByteSink sink = heapSink();
        sink.reset();
        complete(sink);
        return sink.writtenBytes() == 0 ? List.of() : List.of(sink.toCopiedByteArray());
    }

    @Override
    public void onTextDelta(int blockIndex, CharSequence textDelta) {
        if (textDelta == null || textDelta.isEmpty()) {
            return;
        }
        completionCounter.add(textDelta);
        encoder.encodeTextDelta(blockIndex, textDelta, activeOut);
    }

    @Override
    public void onThinkingDelta(int blockIndex, CharSequence thinkingDelta) {
        if (thinkingDelta == null || thinkingDelta.isEmpty()) {
            return;
        }
        completionCounter.add(thinkingDelta);
        encoder.encodeThinkingDelta(blockIndex, thinkingDelta, activeOut);
    }

    @Override
    public void onRefusalDelta(int blockIndex, CharSequence refusalDelta) {
        if (refusalDelta == null || refusalDelta.isEmpty()) {
            return;
        }
        completionCounter.add(refusalDelta);
        encoder.encodeRefusalDelta(blockIndex, refusalDelta, activeOut);
    }

    @Override
    public void onToolCallArgsDelta(int blockIndex, String callId, CharSequence partialJsonDelta) {
        if (partialJsonDelta == null || partialJsonDelta.isEmpty()) {
            return;
        }
        completionCounter.add(partialJsonDelta);
        encoder.encodeToolCallArgsDelta(blockIndex, callId, partialJsonDelta, activeOut);
    }

    @Override
    public void onEvent(CanonicalStreamEvent event) {
        observeEvent(event);
        encoder.encode(event, activeOut);
    }

    @Override
    public TokenUsage finalizeUsage(boolean cleanCompletion) {
        if (cleanCompletion && sawUsage && observedUsage != null) {
            return observedUsage;
        }
        int prompt = (observedUsage != null && observedUsage.totalInputTokens() > 0)
                ? observedUsage.promptTokens()
                : inboundMeta.estimatedPromptTokens();
        int estCompletion = completionCounter.estimateTokens();
        int observedCompletion = observedUsage == null ? 0 : observedUsage.completionTokens();
        int completion = cleanCompletion
                ? (observedCompletion > 0 ? observedCompletion : estCompletion)
                : Math.max(observedCompletion, estCompletion);
        return new TokenUsage(
                prompt,
                observedUsage == null ? 0 : observedUsage.cachedReadTokens(),
                observedUsage == null ? 0 : observedUsage.cachedWriteTokens(),
                completion,
                observedUsage == null ? 0 : observedUsage.reasoningTokens(),
                true
        );
    }

    private ByteSink.HeapByteSink heapSink() {
        if (reusableHeapSink == null) {
            reusableHeapSink = new ByteSink.HeapByteSink(512);
        }
        return reusableHeapSink;
    }

    private void observeEvent(CanonicalStreamEvent event) {
        switch (event) {
            case CanonicalStreamEvent.TextDelta td -> completionCounter.add(td.textDelta());
            case CanonicalStreamEvent.ThinkingDelta th -> completionCounter.add(th.thinkingDelta());
            case CanonicalStreamEvent.RefusalDelta ref -> completionCounter.add(ref.refusalDelta());
            case CanonicalStreamEvent.ToolCallArgsDelta tcad -> completionCounter.add(tcad.partialJsonDelta());
            case CanonicalStreamEvent.UsageUpdate uu -> {
                if (uu.usage() != null) {
                    observedUsage = (uu.cumulative() || observedUsage == null)
                            ? uu.usage()
                            : observedUsage.mergeMax(uu.usage());
                    sawUsage = true;
                }
            }
            default -> {}
        }
    }
}
