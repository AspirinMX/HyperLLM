package app.pagefault.hyperllm.console.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableConfigurationProperties(ConsoleProperties.class)
public class ConsoleConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The same snapshot script the gateway uses, loaded from the shared resources so the control
     * plane and the data plane can never read a tenant's balance in two different ways.
     */
    @SuppressWarnings("rawtypes")
    @Bean
    public org.springframework.data.redis.core.script.RedisScript<java.util.List> walletSnapshotScript() {
        return org.springframework.data.redis.core.script.RedisScript.of(
                new org.springframework.core.io.ClassPathResource("lua/prehold_snapshot.lua"),
                java.util.List.class);
    }

    @Bean
    public OpenAPI consoleOpenApi() {
        final String schemeName = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("HyperLLM Console API")
                        .version("v1")
                        .description("Developer workspace, API key, wallet, and model catalog control plane"))
                .addSecurityItem(new SecurityRequirement().addList(schemeName))
                .components(new Components().addSecuritySchemes(schemeName,
                        new SecurityScheme()
                                .name(schemeName)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
