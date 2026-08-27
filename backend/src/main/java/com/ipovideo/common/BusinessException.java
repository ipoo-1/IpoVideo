package com.ipovideo.common;

/**
 * 业务异常：携带业务错误码。
 * 本阶段约定错误码与 HTTP 状态码保持一致，例如 400 参数错误、401 未登录、409 冲突。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
