package com.pokemon.offline.platform.beans.factory;

/** 在 Android 组合根中延迟实例化共享组件。 */
public interface ObjectProvider<T> {
    T getIfAvailable();
}
