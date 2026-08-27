package com.ipovideo.controller;

import com.ipovideo.common.Result;
import com.ipovideo.config.AuthInterceptor;
import com.ipovideo.dto.ChangePasswordRequest;
import com.ipovideo.dto.UserView;
import com.ipovideo.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录后才能访问：token 由 AuthInterceptor 校验，用户 ID 放在 request attribute 里。
     */
    @GetMapping("/api/me")
    public Result<UserView> me(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE);
        return Result.ok(authService.getUserViewById(userId));
    }
    @PutMapping("/api/me/password")
    public Result<Void> changePasswordRequest(
            @Valid @RequestBody ChangePasswordRequest request, HttpServletRequest http) {
        Long userId = (Long) http.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE);
        authService.changePassword(userId,request);
        return Result.ok();

    }

}
