package com.ipovideo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipovideo.common.BusinessException;
import com.ipovideo.config.RedisKeys;
import com.ipovideo.dto.ChangePasswordRequest;
import com.ipovideo.dto.LoginRequest;
import com.ipovideo.dto.LoginResponse;
import com.ipovideo.dto.RegisterRequest;
import com.ipovideo.dto.UserView;
import com.ipovideo.entity.User;
import com.ipovideo.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 认证核心业务：注册、登录、登出、按 token 找用户。
 * Stage 4：登录会话从 MySQL 迁到 Redis，并加入登录失败限流。
 */
@Service
public class AuthService {

    private static final int MAX_LOGIN_FAILURES = 8;
    private static final long LOGIN_FAILURE_WINDOW_MINUTES = 10;

    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final PasswordHasher passwordHasher;
    private final int sessionExpireHours;

    public AuthService(UserMapper userMapper,
                       StringRedisTemplate redisTemplate,
                       PasswordHasher passwordHasher,
                       @Value("${app.session.expire-hours:24}") int sessionExpireHours) {
        this.userMapper = userMapper;
        this.redisTemplate = redisTemplate;
        this.passwordHasher = passwordHasher;
        this.sessionExpireHours = sessionExpireHours;
    }

    @Transactional
    public UserView register(RegisterRequest request) {
        String username = request.username().trim();

        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", username);
        if (userMapper.selectCount(query) > 0) {
            throw new BusinessException(409, "该账号已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash(request.password()));
        user.setNickname(blankToDefault(request.nickname(), "用户" + username));
        user.setRole("USER");
        user.setCreatedAt(LocalDateTime.now());

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(409, "该账号已存在");
        }
        return UserView.from(user);
    }

    public LoginResponse login(LoginRequest request) {
        String username = request.username().trim();
        if (username.isBlank() || request.password() == null || request.password().isBlank()) {
            throw new BusinessException(400, "请输入账号和密码");
        }
        if (!loginAttemptAllowed(username)) {
            throw new BusinessException(429, "登录尝试过于频繁，请稍后再试");
        }

        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", username);
        User user = userMapper.selectOne(query);

        if (user == null || !passwordHasher.matches(request.password(), user.getPasswordHash())) {
            recordLoginFailure(username);
            throw new BusinessException(401, "账号或密码错误");
        }

        clearLoginFailures(username);

        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                RedisKeys.sessionKey(token),
                String.valueOf(user.getId()),
                Duration.ofHours(sessionExpireHours));
        redisTemplate.opsForSet().add(RedisKeys.userSessionsKey(user.getId()), token);

        return new LoginResponse(token, UserView.from(user));
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String sessionKey = RedisKeys.sessionKey(token);
        String userId = redisTemplate.opsForValue().get(sessionKey);
        redisTemplate.delete(sessionKey);
        if (userId != null) {
            redisTemplate.opsForSet().remove(RedisKeys.userSessionsKey(Long.valueOf(userId)), token);
        }
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (!passwordHasher.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException(401, "旧密码错误");
        }
        user.setPasswordHash(passwordHasher.hash(request.newPassword()));
        userMapper.updateById(user);

        // 安全规则：改密后让所有旧会话失效
        Set<String> tokens = redisTemplate.opsForSet().members(RedisKeys.userSessionsKey(userId));
        if (tokens != null) {
            for (String oldToken : tokens) {
                redisTemplate.delete(RedisKeys.sessionKey(oldToken));
            }
        }
        redisTemplate.delete(RedisKeys.userSessionsKey(userId));
    }

    public User requireUserByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(401, "请先登录");
        }
        String userId = redisTemplate.opsForValue().get(RedisKeys.sessionKey(token));
        if (userId == null) {
            throw new BusinessException(401, "登录已过期，请重新登录");
        }
        User user = userMapper.selectById(Long.valueOf(userId));
        if (user == null) {
            throw new BusinessException(401, "账号不存在");
        }
        return user;
    }

    public UserView getUserViewById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return UserView.from(user);
    }

    private boolean loginAttemptAllowed(String username) {
        String count = redisTemplate.opsForValue().get(RedisKeys.loginFailuresKey(username));
        if (count == null) {
            return true;
        }
        try {
            return Integer.parseInt(count) < MAX_LOGIN_FAILURES;
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    private void recordLoginFailure(String username) {
        String key = RedisKeys.loginFailuresKey(username);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofMinutes(LOGIN_FAILURE_WINDOW_MINUTES));
        }
    }

    private void clearLoginFailures(String username) {
        redisTemplate.delete(RedisKeys.loginFailuresKey(username));
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
