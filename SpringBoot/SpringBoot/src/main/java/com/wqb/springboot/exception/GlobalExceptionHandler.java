package com.wqb.springboot.exception;

import com.wqb.springboot.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(exception.getMessage(), null));
    }

    /**
     * 未授权异常处理：未登录/token 过期时返回 401，前端据此自动重新登录
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorizedException(UnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(exception.getMessage(), null));
    }

    /**
     * 缺少必填请求参数：返回 400 而非 500
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("缺少必填参数: " + exception.getParameterName(), null));
    }

    /**
     * 不支持的请求 Content-Type：@RequestBody 接口只接收 application/json，
     * 当调用方误用 application/x-www-form-urlencoded 等表单格式时返回 415 并给出明确提示，
     * 而不是落到兜底的 500「服务暂时不可用」。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.fail("请求格式不支持：该接口请使用 JSON 提交（请求头 Content-Type 设为 application/json）", null));
    }

    /**
     * 请求体缺失、为空或 JSON 语法/字段类型错误：返回 400 并提示检查提交内容
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("请求数据格式错误或为空，请检查提交的 JSON 内容是否完整", null));
    }

    /**
     * 访问了不存在的路径（例如直接打开根路径 / —— 本项目后端只提供 /api/** 与 /uploads/**，
     * 后台管理界面是独立的 Vue 工程，不由此服务提供）：
     * 返回 404 并说明原因，而不是落到兜底的 500「服务暂时不可用」。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("接口或资源不存在: " + exception.getResourcePath(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("服务暂时不可用: " + exception.getMessage(), null));
    }
}
