package app.pagefault.hyperllm.console.web;

import java.util.UUID;

/**
 * Authenticated developer identity bound to the current request thread.
 */
public record ConsolePrincipal(
        UUID userId,
        String email
) {
}
