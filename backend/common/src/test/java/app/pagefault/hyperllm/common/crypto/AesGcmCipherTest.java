package app.pagefault.hyperllm.common.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCipherTest {

    private static String b64Key(byte fill) {
        byte[] raw = new byte[AesGcmCipher.KEY_BYTES];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (fill + i);
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void encryptsWithVersionHeaderAndBindsAad() {
        AesGcmCipher cipher = AesGcmCipher.fromBase64((byte) 1, b64Key((byte) 10));
        byte[] aad = "channel-123".getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = "{\"apiKey\":\"sk-upstream-secret\"}".getBytes(StandardCharsets.UTF_8);

        byte[] envelope = cipher.encrypt(plaintext, aad);

        assertThat(envelope[0]).isEqualTo((byte) 1);
        assertThat(envelope.length).isEqualTo(1 + AesGcmCipher.IV_BYTES + plaintext.length + 16);
        assertThat(cipher.decrypt(envelope, aad)).isEqualTo(plaintext);

        assertThatThrownBy(() -> cipher.decrypt(envelope, "channel-999".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptsOlderVersionAfterKeyRotation() {
        AesGcmCipher v1 = AesGcmCipher.fromBase64((byte) 1, b64Key((byte) 1));
        byte[] oldEnvelope = v1.encryptUtf8("secret-v1", "aad-1");

        AesGcmCipher rotated = AesGcmCipher.fromBase64((byte) 2, Map.of(
                (byte) 1, b64Key((byte) 1),
                (byte) 2, b64Key((byte) 99)
        ));

        assertThat(rotated.decryptUtf8(oldEnvelope, "aad-1")).isEqualTo("secret-v1");
        byte[] newEnvelope = rotated.encryptUtf8("secret-v2", "aad-2");
        assertThat(newEnvelope[0]).isEqualTo((byte) 2);
        assertThat(rotated.decryptUtf8(newEnvelope, "aad-2")).isEqualTo("secret-v2");
    }
}
