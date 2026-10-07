package app.pagefault.hyperllm.common.net;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CidrMatcherTest {

    @Test
    void matchesIpv4AndIpv6CidrsCorrectly() {
        assertThat(CidrMatcher.isAllowed("192.168.1.42", null)).isTrue();
        assertThat(CidrMatcher.isAllowed("192.168.1.42", List.of())).isTrue();
        assertThat(CidrMatcher.isAllowed("10.20.30.40", List.of("10.0.0.0/8"))).isTrue();
        assertThat(CidrMatcher.isAllowed("203.0.113.5", List.of("203.0.113.5/32"))).isTrue();
        assertThat(CidrMatcher.isAllowed("203.0.113.6", List.of("203.0.113.5/32"))).isFalse();
        assertThat(CidrMatcher.isAllowed("2001:db8::42", List.of("2001:db8::/32"))).isTrue();
        assertThat(CidrMatcher.isAllowed("2001:db9::42", List.of("2001:db8::/32"))).isFalse();
    }
}
