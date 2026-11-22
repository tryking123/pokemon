package com.pokemon.offline.platform.beans.factory.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * App 离线组件装配标记，由应用运行时读取。
 *
 * @author tongruiyuan
 * @since 2026-11-21 23:51
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Value { String value(); }
