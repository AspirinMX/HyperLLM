package app.pagefault.hyperllm.domain;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("integration")
@SpringBootTest(classes = AbstractPostgresIntegrationTest.TestApp.class)
public abstract class AbstractPostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hyperllm_test")
            .withUsername("hyperllm")
            .withPassword("hyperllm");

    static {
        POSTGRES.start();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }
}
