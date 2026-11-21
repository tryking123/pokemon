package com.pokemon.local.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 本地接口错误响应转换
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:01
 */
@RestControllerAdvice
public class ApiErrorHandler {

    /**
     * 转换业务错误
     *
     * @param exception 业务异常
     * @return HTTP 错误响应
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException exception) {
        return error(exception.getStatus(), exception.getCode(), exception.getMessage());
    }

    /**
     * 转换请求体解析错误
     *
     * @return HTTP 错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleMalformedBody() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "请提供有效的 JSON 请求体");
    }

    /**
     * 构造错误响应
     *
     * @param status HTTP 状态
     * @param code 业务错误码
     * @param message 中文错误说明
     * @return HTTP 错误响应
     */
    private ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
