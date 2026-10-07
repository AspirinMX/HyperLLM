package app.pagefault.hyperllm.domain;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.enums.UserStatus;
import app.pagefault.hyperllm.domain.model.auth.User;
import app.pagefault.hyperllm.domain.repository.CustomerTierRepository;
import app.pagefault.hyperllm.domain.repository.ProviderRepository;
import app.pagefault.hyperllm.domain.repository.UserRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;

class FlywayAndPartitionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private CustomerTierRepository customerTierRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DSLContext dsl;

    @Test
    void seedsStandardTierAndDefaultProviders() {
        assertThat(customerTierRepository.findActiveByCode("STANDARD")).isPresent();
        assertThat(providerRepository.listAllActive())
                .extracting(p -> p.code())
                .contains("OPENAI", "ANTHROPIC", "GOOGLE_GEMINI", "AWS_BEDROCK", "AZURE_OPENAI", "VERTEX_AI", "VLLM", "DEEPSEEK");
    }

    @Test
    void softDeletedEmailAllowsReRegistration() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID firstId = UuidV7.next();
        String email = "recreate-" + firstId + "@example.com";

        userRepository.insert(new User(
                firstId, email, now, "First User", null, "en-US", "USD",
                null, UserStatus.ACTIVE, null, null, 0L, now, now
        ));

        dsl.update(USERS)
                .set(USERS.STATUS, UserStatus.DELETED.name())
                .set(USERS.DELETED_AT, now)
                .where(USERS.ID.eq(firstId))
                .execute();

        UUID secondId = UuidV7.next();
        userRepository.insert(new User(
                secondId, email.toUpperCase(), now, "Second User", null, "zh-CN", "CNY",
                null, UserStatus.ACTIVE, null, null, 0L, now, now
        ));

        assertThat(userRepository.findActiveByEmail(email))
                .isPresent()
                .get()
                .extracting(User::id)
                .isEqualTo(secondId);
    }
}
