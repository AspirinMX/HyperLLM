package app.pagefault.hyperllm.console.payment;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** RSA signatures and AES-GCM decryption used by the channel adapters. */
public final class PaymentCrypto {

    private PaymentCrypto() {
    }

    public static PrivateKey privateKey(String pemOrBase64) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPem(pemOrBase64));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Cannot load channel private key", ex);
        }
    }

    public static PublicKey publicKey(String pemOrBase64) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPem(pemOrBase64));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Cannot load channel public key", ex);
        }
    }

    public static String signSha256Rsa(String content, PrivateKey key) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot sign channel request", ex);
        }
    }

    public static boolean verifySha256Rsa(String content, String base64Signature, PublicKey key) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(base64Signature));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            return false;
        }
    }

    public static String decryptAesGcm(byte[] key, String base64Ciphertext, String nonce, String associatedData) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(Base64.getDecoder().decode(base64Ciphertext)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Cannot decrypt channel notification", ex);
        }
    }

    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripPem(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Empty key");
        }
        return value.replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "");
    }
}
