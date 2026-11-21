package com.pokemon.offline.embedded;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.pokemon.offline.bootstrap.BootstrapServiceImpl;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.beans.factory.ObjectProvider;
import com.pokemon.offline.platform.beans.factory.annotation.Value;
import com.pokemon.offline.platform.context.annotation.Lazy;
import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.core.io.ClassPathResource;

/** 装配共享游戏组件。Android 仅装载业务和协议适配类。 */
final class ComponentGraph {
    private final List<Class<?>> components = new ArrayList<>();
    private final Map<Class<?>, Object> instances = new HashMap<>();
    private final Set<Class<?>> constructing = new HashSet<>();

    ComponentGraph() throws IOException, ClassNotFoundException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("components.txt").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    components.add(Class.forName(line, false, getClass().getClassLoader()));
                } catch (ClassNotFoundException missing) {
                    throw new IOException("组件清单缺少实现类：" + line, missing);
                }
            }
        }
        components.sort(Comparator.comparingInt(this::order));
    }

    <T> void bind(Class<T> type, T value) {
        instances.put(type, value);
    }

    <T> T get(Class<T> type) {
        Object bound = instances.get(type);
        if (bound != null) {
            return type.cast(bound);
        }
        List<Class<?>> candidates = candidates(type);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("本地组件未注册实现：" + type.getName());
        }
        if (candidates.size() != 1) {
            throw new IllegalStateException("本地组件配置不唯一：" + type.getName() + " " + candidates);
        }
        return type.cast(create(candidates.get(0)));
    }

    /** 在首次使用时解析构造器依赖。循环依赖仅允许通过显式延迟接口。 */
    private Object create(Class<?> type) {
        Object existing = instances.get(type);
        if (existing != null) {
            return existing;
        }
        if (!constructing.add(type)) {
            throw new IllegalStateException("本地组件循环依赖：" + type.getName());
        }
        try {
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length != 1) {
                throw new IllegalStateException("本地组件必须有唯一构造器：" + type.getName());
            }
            Constructor<?> constructor = constructors[0];
            constructor.setAccessible(true);
            Parameter[] parameters = constructor.getParameters();
            Object[] arguments = new Object[parameters.length];
            for (int index = 0; index < parameters.length; index++) {
                arguments[index] = resolve(parameters[index]);
            }
            Object instance = constructor.newInstance(arguments);
            initialize(type, instance);
            instances.put(type, instance);
            return instance;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Throwable cause = exception instanceof InvocationTargetException ? exception.getCause() : exception;
            throw new IllegalStateException("本地组件初始化失败：" + type.getName(), cause);
        } finally {
            constructing.remove(type);
        }
    }

    private Object resolve(Parameter parameter) {
        Class<?> type = parameter.getType();
        Value value = parameter.getAnnotation(Value.class);
        if (value != null) {
            return setting(value.value(), type);
        }
        if (type == List.class || type == ObjectProvider.class) {
            ParameterizedType collection = (ParameterizedType) parameter.getParameterizedType();
            Class<?> element = (Class<?>) collection.getActualTypeArguments()[0];
            if (type == ObjectProvider.class) {
                return (ObjectProvider<?>) () -> get(element);
            }
            List<Object> items = new ArrayList<>();
            candidates(element).forEach(candidate -> items.add(create(candidate)));
            return items;
        }
        if (type.isInterface() && parameter.isAnnotationPresent(Lazy.class)) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                try {
                    return method.invoke(get(type), args);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            });
        }
        return get(type);
    }

    private Object setting(String expression, Class<?> type) {
        String item = expression.substring(2, expression.length() - 1);
        int separator = item.indexOf(':');
        String name = separator < 0 ? item : item.substring(0, separator);
        String value = separator < 0 ? "" : item.substring(separator + 1);
        if (name.equals("pokemon.products")) {
            value = "diamond_60:600:60,diamond_300:3000:300,diamond_680:6800:680,"
                    + "diamond_1280:12800:1280,diamond_3280:32800:3280,diamond_6480:64800:6480";
        }
        try {
            if (type == int.class) {
                return Integer.parseInt(value);
            }
            if (type == boolean.class) {
                return Boolean.parseBoolean(value);
            }
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("本地配置值无法解析：" + expression, invalid);
        }
        return value;
    }

    private void initialize(Class<?> type, Object instance) throws ReflectiveOperationException {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostConstruct.class) && !desktopLifecycle(type)) {
                method.setAccessible(true);
                method.invoke(instance);
            }
        }
    }

    /** Android 存档从 SQLite 结构初始化，两项 H2 旧格式迁移及网络启动由各自运行环境处理。 */
    private boolean desktopLifecycle(Class<?> type) {
        String name = type.getSimpleName();
        return name.equals("ElfMapper") || name.equals("GamePaymentMapper") || name.equals("OfflineGameSession");
    }

    private List<Class<?>> candidates(Class<?> type) {
        List<Class<?>> matches = new ArrayList<>();
        for (Class<?> component : components) {
            if (type.isAssignableFrom(component)) {
                matches.add(component);
            }
        }
        return matches;
    }

    private int order(Class<?> type) {
        Order annotation = type.getAnnotation(Order.class);
        return annotation == null ? Integer.MAX_VALUE : annotation.value();
    }
}
