package com.faas.registry;

import com.faas.LocalLambdaFunction;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of available local functions.
 * <p>
 * Thread-safe and very lightweight. Intended to be populated on application
 * startup (e.g. via Spring configuration).
 */
public class FunctionRegistry {

    private final Map<String, LocalLambdaFunction> functions = new ConcurrentHashMap<>();

    /**
     * Register single function instance.
     */
    public void register(LocalLambdaFunction function) {
        if (function == null) {
            return;
        }
        functions.put(function.getName(), function);
    }

    /**
     * Register all functions from given collection.
     */
    public void registerAll(Collection<? extends LocalLambdaFunction> functions) {
        if (functions == null) {
            return;
        }
        for (LocalLambdaFunction fn : functions) {
            register(fn);
        }
    }

    /**
     * Retrieve function by name.
     */
    public LocalLambdaFunction get(String name) {
        return functions.get(name);
    }

    /**
     * Check whether function with given name exists.
     */
    public boolean exists(String name) {
        return functions.containsKey(name);
    }

    /**
     * Unmodifiable view of all functions.
     */
    public Map<String, LocalLambdaFunction> getAll() {
        return Collections.unmodifiableMap(functions);
    }
}
