package app.pagefault.hyperllm.common.id;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void generatesVersion7AndMonotonicallyIncreasing() {
        UUID first = UuidV7.next();
        UUID second = UuidV7.next();

        assertThat(first.version()).isEqualTo(7);
        assertThat(second.version()).isEqualTo(7);
        assertThat(second.compareTo(first)).isGreaterThan(0);
    }
}
