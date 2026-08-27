package com.ipovideo.service;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希工具：PBKDF2（JDK 自带，无需额外依赖）。
 *
 * <p>核心思想：加盐 + 多次迭代，让"猜密码"的代价足够高。
 * 存储格式：pbkdf2$迭代次数$盐$哈希值，salt 随机生成后与哈希一起保存。</p>
 */
@Component
public class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String FORMAT = "pbkdf2$%d$%s$%s";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;

    private final SecureRandom secureRandom = new SecureRandom();

    public String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] derived = derive(rawPassword.toCharArray(), salt, ITERATIONS);
        return FORMAT.formatted(
                ITERATIONS,
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(derived)
        );
    }

    public boolean matches(String rawPassword, String stored) {
        if (stored == null || !stored.startsWith("pbkdf2$")) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(rawPassword.toCharArray(), salt, iterations);
            // 用常量时间比较，避免通过时间差猜测哈希
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ex) {
            return false;
        }
    }

    private byte[] derive(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("密码哈希计算失败", ex);
        }
    }
}
