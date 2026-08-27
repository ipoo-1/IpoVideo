package com.ipovideo.common;

/**
 * 统一 API 响应体。
 *
 * <p>约定：{@code code == 0} 表示成功；非 0 表示业务错误。
 * 前端只用写一套解析逻辑，所有接口返回结构一致。</p>
 */
public record Result<T>(int code, String message, T data) {

    public static final int SUCCESS = 0;

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS, "success", data);
    }

    public static <T> Result<T> ok() {
        return new Result<>(SUCCESS, "success", null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
