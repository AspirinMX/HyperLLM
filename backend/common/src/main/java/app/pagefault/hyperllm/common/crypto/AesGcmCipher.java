package app.pagefault.hyperllm.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned AES-256-GCM envelope cipher with optional Associated Authenticated Data (AAD).
 *
 * <p>Binary layout: {@code [1B key_version][12B random IV][ciphertext + 16B GCM auth tag]}.
 */
public final class AesGcmCipher {

    public static final int KEY_BYTES = 32;
    public static final int IV_BYTES = 12;
    public static final int TAG_BITS = 128;
    private static final int HEADER_AND_TAG_BYTES = 1 + IV_BYTES + (TAG_BITS / 8);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte currentVersion;
    private final Map<Byte, SecretKey> keys;

    private AesGcmCipher(byte currentVersion, Map<Byte, SecretKey> keys) {
        if (!keys.containsKey(currentVersion)) {
            throw new IllegalArgumentException("Current key version " + (currentVersion & 0xFF) + " missing from key map");
        }
        this.currentVersion = currentVersion;
        this.keys = Map.copyOf(keys);
    }

    public static AesGcmCipher fromBase64(byte version, String base64Key) {
        return fromBase64(version, Map.of(version, base64Key));
    }

    public static AesGcmCipher fromBase64(byte currentVersion, Map<Byte, String> versionedBase64Keys) {
        Objects.requireNonNull(versionedBase64Keys, "versionedBase64Keys must not be null");
        Map<Byte, SecretKey> parsed = new HashMap<>(versionedBase64Keys.size());
        for (Map.Entry<Byte, String> entry : versionedBase64Keys.entrySet()) {
            Byte ver = Objects.requireNonNull(entry.getKey(), "Key version must not be null");
            if (ver <= 0) {
                throw new IllegalArgumentException("Key version must be positive: " + ver);
            }
            byte[] raw = decodeKey(entry.getValue());
            parsed.put(ver, new SecretKeySpec(raw, "AES"));
        }
        return new AesGcmCipher(currentVersion, parsed);
    }

    public byte currentVersion() {
        return currentVersion;
    }

    public byte[] encrypt(byte[] plaintext, byte[] aad) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        SecretKey key = keys.get(currentVersion);
        byte[] iv = new byte[IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            byte[] encrypted = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(1 + IV_BYTES + encrypted.length)
                    .put(currentVersion)
                    .put(iv)
                    .put(encrypted)
                    .array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-256-GCM encryption failed", ex);
        }
    }

    public byte[] encryptUtf8(String plaintext, String aad) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        byte[] aadBytes = aad == null ? null : aad.getBytes(StandardCharsets.UTF_8);
        return encrypt(plaintext.getBytes(StandardCharsets.UTF_8), aadBytes);
    }

    public byte[] decrypt(byte[] envelope, byte[] aad) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        if (envelope.length < HEADER_AND_TAG_BYTES) {
            throw new IllegalArgumentException("Encrypted envelope shorter than minimum AES-GCM frame size");
        }
        byte version = envelope[0];
        SecretKey key = keys.get(version);
        if (key == null) {
            throw new IllegalArgumentException("Unknown encryption key version: " + (version & 0xFF));
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, envelope, 1, IV_BYTES));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(envelope, 1 + IV_BYTES, envelope.length - 1 - IV_BYTES);
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("AES-256-GCM decryption or authentication failed", ex);
        }
    }

    public String decryptUtf8(byte[] envelope, String aad) {
        byte[] aadBytes = aad == null ? null : aad.getBytes(StandardCharsets.UTF_8);
        return new String(decrypt(envelope, aadBytes), StandardCharsets.UTF_8);
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("AES-256-GCM Base64 key must not be blank");
        }
        byte[] decoded = Base64.getDecoder().decode(base64Key.trim());
        if (decoded.length != KEY_BYTES) {
            throw new IllegalArgumentException("Expected 32-byte AES-256 key, got " + decoded.length + " bytes");
        }
        return decoded;
    }
}
