package com.ipovideo.config;

import com.ipovideo.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录校验拦截器：在进入 Controller 之前检查 Authorization 头。
 * 校验通过后把 userId 放进 request attribute，Controller 直接取用。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID_ATTRIBUTE = "authenticatedUserId";

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long userId = authService.requireUserByToken(resolveToken(request)).getId();
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        return true;
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        if (header != null && !header.isBlank()) {
            return header;
        }
        return null;
    }
}
