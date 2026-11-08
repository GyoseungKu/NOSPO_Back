package kr.nospo.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

@Component
public class Secrets {
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec encryption, hmac;

    public Secrets(
            @Value("${nospo.auth.encryption-key}") String encryptionKey,
            @Value("${nospo.auth.hmac-key}") String hmacKey) {
        encryption = key(encryptionKey, "AES");
        hmac = key(hmacKey, "HmacSHA256");
    }

    public static SecretKeySpec key(String value, String algorithm) {
        try {
            byte[] key = Base64.getDecoder().decode(value);
            if (key.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(key, algorithm);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Auth keys must be Base64-encoded 32-byte secrets");
        }
    }

    public String token() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] fingerprint(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(hmac);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] encrypt(byte[] value, String context) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryption, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value);
            byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] value, String context) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryption,
                    new GCMParameterSpec(128, Arrays.copyOf(value, 12)));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(value, 12, value.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encrypted data cannot be read", e);
        }
    }
}
