package com.faas;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр функций БЕЗ Spring.
 * Платформа сама создаёт экземпляр и регистрирует бины-функции.
 */
public class FunctionRegistry {

    private final Map<String, LocalLambdaFunction> functions = new ConcurrentHashMap<>();

    public void register(LocalLambdaFunction function) {
        functions.put(function.getName(), function);
    }

    public void registerAll(Collection<? extends LocalLambdaFunction> list) {
        for (LocalLambdaFunction f : list) {
            register(f);
        }
    }

    public LocalLambdaFunction get(String name) {
        return functions.get(name);
    }

    public boolean exists(String name) {
        return functions.containsKey(name);
    }

    public Map<String, LocalLambdaFunction> getAll() {
        return Map.copyOf(functions);
    }
}
