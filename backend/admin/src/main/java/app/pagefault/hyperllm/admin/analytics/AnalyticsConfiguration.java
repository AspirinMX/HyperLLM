package app.pagefault.hyperllm.admin.analytics;

import app.pagefault.hyperllm.admin.config.AdminProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Report-plane listener wiring. The warehouse pool lives in {@link AnalyticsPool}, outside the
 * application DataSource, so a slow warehouse cannot become the ledger's datasource.
 */
@Configuration(proxyBeanMethods = false)
public class AnalyticsConfiguration {

    /**
     * Batched, manually acknowledged listener for request facts. The batch is acknowledged only
     * after ClickHouse accepted the rows and the committed position moved, so a failure simply
     * replays the batch.
     */
    @Bean(name = "requestEventListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> requestEventListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, AdminProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.setConcurrency(properties.getAnalytics().getConcurrency());
        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(new FixedBackOff(2_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        errorHandler.setAckAfterHandle(false);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
