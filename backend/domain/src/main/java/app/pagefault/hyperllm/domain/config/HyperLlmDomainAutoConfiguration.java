package app.pagefault.hyperllm.domain.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Registers domain jOOQ repositories for downstream Spring Boot applications.
 */
@AutoConfiguration
@ComponentScan("app.pagefault.hyperllm.domain.repository.jooq")
public class HyperLlmDomainAutoConfiguration {
}
