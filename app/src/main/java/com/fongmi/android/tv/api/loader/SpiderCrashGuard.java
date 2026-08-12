package com.fongmi.android.tv.api.loader;

import android.os.Handler;
import android.os.Looper;

import com.github.catvod.crawler.SpiderDebug;

/**
 * Keeps the process alive when dynamically loaded spider code throws on the main thread.
 * Such a fault can surface long after the host's own try/catch has returned - for example a
 * layout listener the jar registered firing during a rotation - so it is only catchable at the
 * message pump. Anything not attributable to a spider jar is rethrown and crashes as before.
 */
public final class SpiderCrashGuard {

    private static boolean installed;

    private SpiderCrashGuard() {
    }

    public static void install() {
        if (installed) return;
        installed = true;
        new Handler(Looper.getMainLooper()).post(SpiderCrashGuard::pump);
    }

    private static void pump() {
        while (true) {
            try {
                Looper.loop();
            } catch (Throwable e) {
                String jarKey = SpiderJarRegistry.attribute(e);
                if (jarKey == null) throw e;
                SpiderIsolation.report(jarKey);
                SpiderDebug.log("spider-guard", "main thread fault swallowed jar=%s error=%s:%s", jarKey, e.getClass().getSimpleName(), e.getMessage());
                SpiderDebug.log("spider-guard", e);
            }
        }
    }
}
