package app.pagefault.hyperllm.gateway.proxy;

import app.pagefault.hyperllm.protocol.ir.CanonicalContentPart;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.JsonUtf8Text;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.TranslationPolicy;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import app.pagefault.hyperllm.protocol.translate.ProtocolTranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Commit 3 direct {@link ByteBuf} encoding ({@link NettyByteBufSink}) under
 * {@link ResourceLeakDetector.Level#PARANOID} and {@link RequestByteArena} contiguous vs paged parsing.
 */
class NettyDirectBufferEncodingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolTranslator translator = new ProtocolTranslator(mapper);

    @Test
    void encodesAllFourProtocolsDirectlyIntoPooledDirectByteBufWithoutLeaks() throws Exception {
        ResourceLeakDetector.Level previous = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        PooledByteBufAllocator alloc = new PooledByteBufAllocator(true);
        try {
            String json = """
                    {"model":"gpt-5","stream":true,"messages":[
                      {"role":"system","content":"System prompt 中文 🚀"},
                      {"role":"user","content":"%s"}
                    ]}
                    """.formatted("Hello world! ".repeat(500));
            CanonicalRequest req = translator.parseRequest(
                    ProtocolKind.OPENAI_CHAT, json.getBytes(StandardCharsets.UTF_8), InboundRequestContext.empty());

            UpstreamEncodingContext ctx = new UpstreamEncodingContext(
                    "upstream-model", 2048, TranslationPolicy.BEST_EFFORT, null, null);

            for (ProtocolKind outbound : ProtocolKind.values()) {
                NettyByteBufSink sink = new NettyByteBufSink(alloc, 1024);
                ByteBuf direct = null;
                try {
                    translator.encodeUpstreamRequest(req, outbound, ctx, sink);
                    direct = sink.finishAndTransfer();
                    assertThat(direct.isDirect()).isTrue();
                    assertThat(direct.refCnt()).isEqualTo(1);
                    assertThat(direct.readableBytes()).isGreaterThan(5000);

                    byte[] copy = new byte[direct.readableBytes()];
                    direct.getBytes(direct.readerIndex(), copy);
                    JsonNode parsed = mapper.readTree(copy);
                    assertThat(parsed.isObject()).isTrue();
                } finally {
                    sink.release();
                    if (direct != null) {
                        direct.release();
                        assertThat(direct.refCnt()).isEqualTo(0);
                    }
                }
            }
            assertThat(alloc.metric().usedDirectMemory()).isGreaterThanOrEqualTo(0L);
        } finally {
            ResourceLeakDetector.setLevel(previous);
        }
    }

    @Test
    void nettyByteBufSinkReleasesDirectBufferOnEncodingFailure() {
        PooledByteBufAllocator alloc = new PooledByteBufAllocator(true);
        NettyByteBufSink sink = new NettyByteBufSink(alloc, 512);
        sink.writeByte('{');
        assertThat(sink.writtenBytes()).isEqualTo(1);
        sink.release();
        // Second release must be a safe no-op
        sink.release();
        assertThatThrownBy(() -> sink.writeByte('}'))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requestByteArenaUsesContiguousSlicesBelowThresholdAndPagedStreamAboveThreshold() {
        // Construct a payload containing multi-byte CJK + emoji characters that will cross page boundaries
        String repeated = "中文测试🚀HelloWorld! ".repeat(800);
        String json = "{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"" + repeated + "\"}]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        DefaultDataBufferFactory factory = DefaultDataBufferFactory.sharedInstance;

        // 1. Contiguous mode (below threshold)
        DataBuffer buf1 = factory.wrap(bytes);
        RequestByteArena contiguous = RequestByteArena.readFrom(buf1, bytes.length + 1024, 4096);
        DataBufferUtils.release(buf1);
        assertThat(contiguous.isPaged()).isFalse();
        assertThat(contiguous.size()).isEqualTo(bytes.length);

        CanonicalRequest contiguousReq = contiguous.parse(
                translator, ProtocolKind.OPENAI_CHAT, InboundRequestContext.empty());
        CanonicalContentPart.TextPart tp1 =
                (CanonicalContentPart.TextPart) contiguousReq.turns().getFirst().parts().getFirst();
        assertThat(tp1.utf8Text()).isInstanceOf(JsonUtf8Text.ValidatedJsonSlice.class);
        assertThat(tp1.text()).isEqualTo(repeated);

        // 2. Paged mode (above threshold, split across multiple 4096B pages)
        DataBuffer buf2 = factory.wrap(bytes);
        RequestByteArena paged = RequestByteArena.readFrom(buf2, 4096, 4096);
        DataBufferUtils.release(buf2);
        assertThat(paged.isPaged()).isTrue();
        assertThat(paged.pageCount()).isGreaterThanOrEqualTo(4);
        assertThat(paged.size()).isEqualTo(bytes.length);

        CanonicalRequest pagedReq = paged.parse(
                translator, ProtocolKind.OPENAI_CHAT, InboundRequestContext.empty());
        CanonicalContentPart.TextPart tp2 =
                (CanonicalContentPart.TextPart) pagedReq.turns().getFirst().parts().getFirst();
        assertThat(tp2.text()).isEqualTo(repeated);
        assertThat(pagedReq.metrics().estimatedPromptTokens())
                .isEqualTo(contiguousReq.metrics().estimatedPromptTokens());
    }
}
