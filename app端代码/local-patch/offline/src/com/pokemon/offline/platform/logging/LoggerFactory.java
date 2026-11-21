package com.pokemon.offline.platform.logging;

import android.util.Log;

/**
 * 将内置游戏网关的日志写入 Android 日志。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:47
 */
public final class LoggerFactory {
    private LoggerFactory() {
    }

    /**
     * 按记录日志的类创建 Android 日志适配器。
     *
     * @param source 记录日志的类
     * @return 具备网关所用方法的日志对象
     */
    public static Logger getLogger(Class<?> source) {
        return new AndroidLogger(source.getSimpleName());
    }

    /**
     * 负责日志级别、占位符和异常输出的适配器。
     *
     * @author tongruiyuan
     * @since 2026-11-18 21:47
     */
    private static final class AndroidLogger implements Logger {
        private final String tag;

        private AndroidLogger(String tag) {
            this.tag = tag;
        }

        /** {@inheritDoc} */
        @Override
        public void debug(String template, Object... arguments) {
            if (Log.isLoggable(tag, Log.DEBUG)) {
                write(Log.DEBUG, template, arguments);
            }
        }

        /** {@inheritDoc} */
        @Override
        public void info(String template, Object... arguments) {
            write(Log.INFO, template, arguments);
        }

        /** {@inheritDoc} */
        @Override
        public void warn(String template, Object... arguments) {
            write(Log.WARN, template, arguments);
        }

        /**
         * 按顺序代入占位符并输出可选异常。
         *
         * @param priority Android 日志级别
         * @param template 日志模板
         * @param arguments 已传入的模板参数
         */
        private void write(int priority, String template, Object[] arguments) {
            int size = arguments.length;
            Throwable cause = size > 0 && arguments[size - 1] instanceof Throwable
                    ? (Throwable) arguments[--size] : null;
            StringBuilder message = new StringBuilder();
            int start = 0;
            for (int i = 0; i < size; i++) {
                int next = template.indexOf("{}", start);
                if (next < 0) {
                    break;
                }
                message.append(template, start, next).append(arguments[i]);
                start = next + 2;
            }
            message.append(template, start, template.length());
            if (cause != null) {
                message.append('\n').append(Log.getStackTraceString(cause));
            }
            Log.println(priority, tag, message.toString());
        }
    }
}
