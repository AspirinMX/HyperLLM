package app.pagefault.hyperllm.common.cache;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads classpath Lua scripts eagerly into memory as static strings so {@link RedisScript#getSha1()}
 * uses {@code StaticScriptSource} rather than {@code ResourceScriptSource}, avoiding repeated
 * fat-JAR {@code FileNotFoundException} throws and {@code statx} syscalls on every Redis eval.
 */
public final class LuaScripts {

    private LuaScripts() {
    }

    public static <T> RedisScript<T> loadClasspath(String path, Class<T> resultType) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            RedisScript<T> loaded = RedisScript.of(script, resultType);
            loaded.getSha1();
            return loaded;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load Redis Lua script from classpath: " + path, ex);
        }
    }
}
