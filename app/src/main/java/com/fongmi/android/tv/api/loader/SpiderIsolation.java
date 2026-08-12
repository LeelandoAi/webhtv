package com.fongmi.android.tv.api.loader;

import android.content.SharedPreferences;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;

import java.util.Map;

public final class SpiderIsolation {

    private static final String PREFIX = "spider_fault_";
    private static final int THRESHOLD = 5;

    private SpiderIsolation() {
    }

    static boolean isIsolated(String jarKey) {
        return Prefers.getInt(PREFIX + jarKey) >= THRESHOLD;
    }

    static void report(String jarKey) {
        String key = PREFIX + jarKey;
        int faults = Prefers.getInt(key);
        if (faults >= THRESHOLD) return;
        Prefers.put(key, faults + 1);
        SpiderDebug.log("spider-guard", "jar fault jar=%s faults=%s isolated=%s", jarKey, faults + 1, faults + 1 >= THRESHOLD);
    }

    public static void reset() {
        SharedPreferences prefers = Prefers.getPrefers();
        SharedPreferences.Editor editor = prefers.edit();
        for (Map.Entry<String, ?> entry : prefers.getAll().entrySet()) {
            if (entry.getKey().startsWith(PREFIX)) editor.remove(entry.getKey());
        }
        editor.apply();
    }
}
