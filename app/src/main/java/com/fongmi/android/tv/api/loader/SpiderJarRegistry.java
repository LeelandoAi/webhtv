package com.fongmi.android.tv.api.loader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a stack frame back to the spider jar that defined its class, so a main thread
 * fault can be attributed to one dynamically loaded jar instead of the whole app.
 */
public final class SpiderJarRegistry {

    private static final Map<String, CspDexClassLoader> loaders = new ConcurrentHashMap<>();

    private SpiderJarRegistry() {
    }

    static void register(String jarKey, CspDexClassLoader loader) {
        loaders.put(jarKey, loader);
    }

    static void clear() {
        loaders.clear();
    }

    public static String attribute(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            for (StackTraceElement frame : cause.getStackTrace()) {
                String jarKey = owner(frame.getClassName());
                if (jarKey != null) return jarKey;
            }
        }
        return null;
    }

    private static String owner(String name) {
        for (Map.Entry<String, CspDexClassLoader> entry : loaders.entrySet()) {
            // findLoadedClass only answers for classes this loader defined itself, so host
            // classes reached through parent delegation never match.
            if (entry.getValue().loadedClass(name) != null) return entry.getKey();
        }
        return null;
    }
}
