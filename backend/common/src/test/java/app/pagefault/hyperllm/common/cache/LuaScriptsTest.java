package app.pagefault.hyperllm.common.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuaScriptsTest {

    @Test
    void loadsClasspathLuaEagerlyWithStableSha1() {
        RedisScript<String> script = LuaScripts.loadClasspath("lua/prehold_admit.lua", String.class);

        assertThat(script.getScriptAsString()).isNotBlank();
        assertThat(script.getSha1()).matches("[0-9a-f]{40}");
        assertThat(script.getSha1()).isEqualTo(script.getSha1());
        assertThat(script.getResultType()).isEqualTo(String.class);
    }

    @Test
    void throwsClearErrorWhenScriptMissing() {
        assertThatThrownBy(() -> LuaScripts.loadClasspath("lua/does_not_exist.lua", String.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does_not_exist.lua");
    }
}
