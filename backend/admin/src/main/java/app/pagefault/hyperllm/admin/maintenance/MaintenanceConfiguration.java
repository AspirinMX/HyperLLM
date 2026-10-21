package app.pagefault.hyperllm.admin.maintenance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the periodic maintenance jobs. A single switch so a rollback can stop every scheduler at
 * once instead of relying on four separate flags.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "hyperllm.maintenance.enabled", havingValue = "true", matchIfMissing = true)
public class MaintenanceConfiguration {
}
