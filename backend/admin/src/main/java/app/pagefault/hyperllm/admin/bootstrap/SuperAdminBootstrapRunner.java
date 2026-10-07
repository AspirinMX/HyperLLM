package app.pagefault.hyperllm.admin.bootstrap;

import app.pagefault.hyperllm.admin.config.AdminProperties;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.auth.AdminUser;
import app.pagefault.hyperllm.domain.model.enums.AdminRole;
import app.pagefault.hyperllm.domain.model.enums.AdminStatus;
import app.pagefault.hyperllm.domain.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Idempotently seeds the initial {@code SUPER_ADMIN} operator account from environment variables
 * when the {@code admin_users} table is empty.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuperAdminBootstrapRunner implements ApplicationRunner {

    private final AdminProperties properties;
    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.getAdmin().getBootstrap().getEmail();
        String password = properties.getAdmin().getBootstrap().getPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (adminUserRepository.countAll() > 0L) {
            return;
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String username = deriveUsername(normalizedEmail);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        adminUserRepository.insert(new AdminUser(
                UuidV7.next(),
                username,
                normalizedEmail,
                passwordEncoder.encode(password),
                AdminRole.SUPER_ADMIN,
                null,
                false,
                null,
                null,
                AdminStatus.ACTIVE,
                null,
                null,
                0L,
                now,
                now
        ));
        log.info("Bootstrapped initial SUPER_ADMIN account '{}' ({})", username, normalizedEmail);
    }

    private static String deriveUsername(String email) {
        int at = email.indexOf('@');
        String local = (at > 0) ? email.substring(0, at) : "superadmin";
        String clean = local.replaceAll("[^a-z0-9_.-]", "_");
        return clean.isBlank() ? "superadmin" : (clean.length() > 64 ? clean.substring(0, 64) : clean);
    }
}
