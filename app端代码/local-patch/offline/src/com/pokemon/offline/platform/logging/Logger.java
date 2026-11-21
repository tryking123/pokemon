package com.pokemon.offline.platform.logging;

/**
 * 内置游戏网关依赖的日志方法。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:47
 */
public interface Logger {
    /**
     * 记录调试消息。
     *
     * @param template 日志模板
     * @param arguments 模板参数和可选异常
     */
    void debug(String template, Object... arguments);

    /**
     * 记录运行消息。
     *
     * @param template 日志模板
     * @param arguments 模板参数和可选异常
     */
    void info(String template, Object... arguments);

    /**
     * 记录告警消息。
     *
     * @param template 日志模板
     * @param arguments 模板参数和可选异常
     */
    void warn(String template, Object... arguments);
}
