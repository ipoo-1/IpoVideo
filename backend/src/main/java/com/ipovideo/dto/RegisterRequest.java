package com.ipovideo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。用注解声明校验规则，Spring 收到请求后先校验再进业务代码。
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$", message = "用户名需为 3-32 位字母、数字或下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 128, message = "密码长度需为 8-128 位")
        String password,

        @Size(max = 50, message = "昵称不能超过 50 个字符")
        String nickname
) {
}
