package org.thornex.musicparty.room;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 房间密码哈希（PBKDF2-HMAC-SHA256）。
 *
 * <p>旧实现把房间密码明文放在内存和 runtime-config.json 里；多房间后密码变成
 * 每个房间一条，必须落盘，所以不能再用明文。
 *
 * <p>格式：{@code pbkdf2$迭代次数$base64(salt)$base64(hash)}
 */
public final class PasswordHasher {

    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "pbkdf2";

    private PasswordHasher() {
    }

    public static String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) return null;
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(rawPassword.toCharArray(), salt, ITERATIONS);
        Base64.Encoder encoder = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    public static boolean matches(String rawPassword, String stored) {
        if (stored == null || stored.isBlank()) return true;   // 无密码房间
        if (rawPassword == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(rawPassword.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("当前 JVM 不支持 PBKDF2WithHmacSHA256", error);
        } finally {
            spec.clearPassword();
        }
    }
}
