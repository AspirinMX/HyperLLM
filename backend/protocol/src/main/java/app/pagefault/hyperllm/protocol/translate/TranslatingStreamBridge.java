package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.protocol.codec.CodecSupport;
import app.pagefault.hyperllm.protocol.codec.DownstreamStreamEncoder;
import app.pagefault.hyperllm.protocol.codec.UpstreamStreamDecoder;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalStreamEvent;
import app.pagefault.hyperllm.protocol.ir.StreamRequestMetadata;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-protocol streaming pipeline composing {@link UpstreamStreamDecoder} -> usage sniffing ->
 * {@link DownstreamStreamEncoder} with bounded text accumulation for token estimation on abrupt abort.
 * Retains only lightweight {@link StreamRequestMetadata} (~48 bytes) so large multi-turn requests are
 * never pinned in Old Gen across long-lived SSE streams.
 */
public final class TranslatingStreamBridge implements StreamBridge {

    private static final byte[] EMPTY_FRAME = new byte[0];

    private final StreamRequestMetadata inboundMeta;
    private final UpstreamStreamDecoder decoder;
    private final DownstreamStreamEncoder encoder;
    private final TokenEstimator tokenEstimator;
    private final StringBuilder streamedText = new StringBuilder();
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
        this.tokenEstimator = tokenEstimator;
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
    public byte[] feed(byte[] upstreamChunk) {
        List<CanonicalStreamEvent> events = decoder.feed(upstreamChunk);
        return concatenate(translateEvents(events));
    }

    /** Collapses every frame produced by one upstream chunk into a single downstream write. */
    private static byte[] concatenate(List<byte[]> frames) {
        if (frames.isEmpty()) {
            return EMPTY_FRAME;
        }
        if (frames.size() == 1) {
            return frames.get(0);
        }
        int total = 0;
        for (byte[] frame : frames) {
            total += frame.length;
        }
        byte[] merged = new byte[total];
        int offset = 0;
        for (byte[] frame : frames) {
            System.arraycopy(frame, 0, merged, offset, frame.length);
            offset += frame.length;
        }
        return merged;
    }

    @Override
    public List<byte[]> complete() {
        List<byte[]> out = new ArrayList<>();
        out.addAll(translateEvents(decoder.complete()));
        out.addAll(encoder.finish());
        return out;
    }

    @Override
    public TokenUsage finalizeUsage(boolean cleanCompletion) {
        if (cleanCompletion && sawUsage && observedUsage != null) {
            return observedUsage;
        }
        int prompt = (observedUsage != null && observedUsage.totalInputTokens() > 0)
                ? observedUsage.promptTokens()
                : inboundMeta.estimatedPromptTokens();
        int estCompletion = tokenEstimator.estimateCompletionTokens(inboundMeta.model(), streamedText);
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

    private List<byte[]> translateEvents(List<CanonicalStreamEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        List<byte[]> out = new ArrayList<>(events.size());
        for (CanonicalStreamEvent event : events) {
            observeEvent(event);
            out.addAll(encoder.encode(event));
        }
        return out;
    }

    private void observeEvent(CanonicalStreamEvent event) {
        switch (event) {
            case CanonicalStreamEvent.TextDelta td -> CodecSupport.appendBounded(streamedText, td.textDelta());
            case CanonicalStreamEvent.ThinkingDelta th -> CodecSupport.appendBounded(streamedText, th.thinkingDelta());
            case CanonicalStreamEvent.RefusalDelta ref -> CodecSupport.appendBounded(streamedText, ref.refusalDelta());
            case CanonicalStreamEvent.ToolCallArgsDelta tcad ->
                    CodecSupport.appendBounded(streamedText, tcad.partialJsonDelta());
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
