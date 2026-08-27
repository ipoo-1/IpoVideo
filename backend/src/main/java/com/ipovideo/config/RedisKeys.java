package com.ipovideo.config;

/**
 * Redis key 命名统一管理，避免不同业务互相覆盖。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    public static String sessionKey(String token) {
        return "auth:session:" + token;
    }

    public static String userSessionsKey(Long userId) {
        return "auth:user:sessions:" + userId;
    }

    public static String loginFailuresKey(String username) {
        return "auth:login-failures:" + username;
    }

    public static String taskLockKey(Long mediaId) {
        return "task:lock:" + mediaId;
    }

    public static String uploadMetaKey(String uploadId) {
        return "upload:meta:" + uploadId;
    }

    public static String uploadPartsKey(String uploadId) {
        return "upload:parts:" + uploadId;
    }
}
