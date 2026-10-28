package app.pagefault.hyperllm.admin.consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/** Keep failed billing records retriable without committing their offsets. */
@Configuration(proxyBeanMethods = false)
class BillingKafkaConfiguration {

    @Bean
    DefaultErrorHandler billingErrorHandler() {
        var handler = new DefaultErrorHandler(new FixedBackOff(2_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(Map.of(), true);
        handler.setAckAfterHandle(false);
        return handler;
    }
}
