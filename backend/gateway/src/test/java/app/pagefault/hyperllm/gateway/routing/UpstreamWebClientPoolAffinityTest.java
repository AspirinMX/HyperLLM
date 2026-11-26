package app.pagefault.hyperllm.gateway.routing;

import app.pagefault.hyperllm.gateway.config.GatewayConfiguration;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.LoopResources;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamWebClientPoolAffinityTest {

    @Test
    void pinsEachProviderBucketToMatchingSharedEventLoopWithoutDisposingBorrowedLoops() throws Exception {
        LoopResources shared = LoopResources.create("test-shared-io", 3, true);
        GatewayProperties props = new GatewayProperties();
        props.getUpstream().setMaxConnectionsPerProvider(120);
        UpstreamWebClientPool pool = new UpstreamWebClientPool(props, shared);
        try {
            assertThat(pool.eventLoopCount()).isEqualTo(3);
            assertThat(pool.resolveAffinitySlot(Thread.currentThread())).isEqualTo(-1);

            EventLoopGroup group = shared.onClient(true);
            List<EventLoop> loops = new ArrayList<>();
            for (EventExecutor exec : group) {
                loops.add((EventLoop) exec);
            }
            assertThat(loops).hasSize(3);

            for (int i = 0; i < loops.size(); i++) {
                final int expectedSlot = i;
                int resolved = loops.get(i)
                        .submit(() -> pool.resolveAffinitySlot(Thread.currentThread()))
                        .get(5, TimeUnit.SECONDS);
                assertThat(resolved).isEqualTo(expectedSlot);
            }

            WebClient c0 = pool.clientFor("OPENAI", 0);
            WebClient c1 = pool.clientFor("OPENAI", 1);
            WebClient c2 = pool.clientFor("OPENAI", 2);
            assertThat(c0).isSameAs(pool.clientFor("OPENAI", 0));
            assertThat(c0).isNotSameAs(c1);
            assertThat(c1).isNotSameAs(c2);

            // Off-EventLoop calls (-1) fall back to round-robin across existing pinned buckets.
            WebClient rr1 = pool.clientFor("OPENAI", -1);
            WebClient rr2 = pool.clientFor("OPENAI", -1);
            assertThat(rr1).isNotSameAs(rr2);

            assertThat(shared.onChannelClass(SocketChannel.class, group)).isNotNull();

            // Lettuce control-plane I/O is isolated onto a minimal dedicated EventLoop pool (Lettuce MIN_IO_THREADS=2).
            var lettuceRes = new GatewayConfiguration().lettuceClientResources();
            try {
                assertThat(lettuceRes.eventLoopGroupProvider().threadPoolSize()).isEqualTo(2);
            } finally {
                lettuceRes.shutdown(0, 0, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            // Borrowed shared LoopResources must remain active after pool shutdown.
            assertThat(shared.isDisposed()).isFalse();
            shared.dispose();
        }
    }
}
