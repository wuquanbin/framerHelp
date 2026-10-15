package com.wqb.springboot.exception;

/**
 * 未授权异常
 * <p>
 * 用户未登录、token 缺失或已过期时抛出，
 * 由 GlobalExceptionHandler 统一映射为 HTTP 401，
 * 前端收到 401 后会自动静默重新登录并重试原请求。
 * </p>
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException() {
        super("请先登录");
    }

    public UnauthorizedException(String message) {
        super(message);
    }
}
