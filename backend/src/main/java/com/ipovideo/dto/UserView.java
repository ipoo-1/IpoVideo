package com.ipovideo.dto;

import com.ipovideo.entity.User;

import java.time.LocalDateTime;

/**
 * 返回给前端的用户信息。注意：这里故意不包含 passwordHash。
 */
public record UserView(
        Long id,
        String username,
        String nickname,
        String role,
        LocalDateTime createdAt
) {
    public static UserView from(User user) {
        return new UserView(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}
