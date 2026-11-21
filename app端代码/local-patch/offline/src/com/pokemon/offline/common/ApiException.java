package com.pokemon.offline.common;

import com.pokemon.offline.platform.http.HttpStatus;

/**
 * 可转换为 HTTP 响应的业务异常
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:01
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    /**
     * 创建业务异常
     *
     * @param status HTTP 状态
     * @param code 业务错误码
     * @param message 中文错误说明
     */
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * 获取 HTTP 状态
     *
     * @return HTTP 状态
     */
    public HttpStatus getStatus() {
        return status;
    }

    /**
     * 获取业务错误码
     *
     * @return 业务错误码
     */
    public String getCode() {
        return code;
    }
}
