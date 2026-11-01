package app.pagefault.hyperllm.console.payment;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCryptoTest {

    @Test
    void signsAndVerifiesWithRsa() throws Exception {
        KeyPair pair = keyPair();
        String payload = "{\"out_trade_no\":\"HL-1\"}";

        String signature = PaymentCrypto.signSha256Rsa(payload, pair.getPrivate());

        assertThat(PaymentCrypto.verifySha256Rsa(payload, signature, pair.getPublic())).isTrue();
        assertThat(PaymentCrypto.verifySha256Rsa(payload + " ", signature, pair.getPublic())).isFalse();
    }

    @Test
    void decryptsAWechatStyleGcmPayload() throws Exception {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String nonce = "abcdefghijkl";
        String aad = "transaction";
        String plaintext = "{\"trade_state\":\"SUCCESS\"}";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));

        assertThat(PaymentCrypto.decryptAesGcm(key, ciphertext, nonce, aad)).isEqualTo(plaintext);
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
