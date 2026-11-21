package org.springframework.http;

/**
 * 内置游戏服务使用的 HTTP 状态码。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:47
 */
public enum HttpStatus {
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    NOT_FOUND(404),
    CONFLICT(409),
    TOO_MANY_REQUESTS(429),
    SERVICE_UNAVAILABLE(503);

    private final int code;

    /**
     * 保存标准 HTTP 状态号码。
     *
     * @param code HTTP 状态号码
     */
    HttpStatus(int code) {
        this.code = code;
    }

    /**
     * 返回 HTTP 状态号码。
     *
     * @return 三位 HTTP 状态号码
     */
    public int value() {
        return code;
    }
}
