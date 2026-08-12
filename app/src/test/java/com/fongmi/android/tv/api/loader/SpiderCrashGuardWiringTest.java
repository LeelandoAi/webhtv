package com.fongmi.android.tv.api.loader;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpiderCrashGuardWiringTest {

    @Test
    public void guardIsInstalledBeforeAnySpiderJarCanLoad() throws Exception {
        String startup = read("Startup.java");

        int caoc = startup.indexOf("CaocConfig.Builder.create()");
        int install = startup.indexOf("SpiderCrashGuard.install()");

        assertTrue("The crash screen must stay configured", caoc >= 0);
        assertTrue("The guard must be installed during app startup", install > caoc);
    }

    @Test
    public void guardSwallowsOnlyFaultsOwnedByASpiderJar() throws Exception {
        String guard = read("api", "loader", "SpiderCrashGuard.java");

        assertTrue("A nested pump is the only place a listener fault can be caught", guard.contains("Looper.loop();"));
        assertTrue(guard.contains("String jarKey = SpiderJarRegistry.attribute(e);"));
        assertTrue("Unattributable faults must keep crashing the process", guard.contains("if (jarKey == null) throw e;"));
        assertTrue(guard.contains("SpiderIsolation.report(jarKey);"));
    }

    @Test
    public void attributionUsesTheDefiningLoaderNotPackageNames() throws Exception {
        String registry = read("api", "loader", "SpiderJarRegistry.java");
        String loader = read("api", "loader", "CspDexClassLoader.java");

        assertTrue(registry.contains("for (Throwable cause = e; cause != null; cause = cause.getCause())"));
        assertTrue(registry.contains("entry.getValue().loadedClass(name) != null"));
        assertFalse("Host and jar share the com.github.catvod prefix, so names cannot decide ownership", registry.contains("startsWith(\"com.github.catvod"));
        assertTrue(loader.contains("return findLoadedClass(name);"));
    }

    @Test
    public void loaderRegistersEachJarAndSkipsIsolatedOnes() throws Exception {
        String jarLoader = read("api", "loader", "JarLoader.java");

        assertTrue(jarLoader.contains("SpiderJarRegistry.register(key, loader);"));
        assertTrue(jarLoader.contains("SpiderJarRegistry.clear();"));
        assertTrue(jarLoader.contains("if (SpiderIsolation.isIsolated(key)) {"));

        int isolated = jarLoader.indexOf("if (SpiderIsolation.isIsolated(key)) {");
        int download = jarLoader.indexOf("Download.create(jar, Path.jar(jar))");
        assertTrue("An isolated jar must not be downloaded or loaded again", isolated < download);
    }

    @Test
    public void isolationIsBoundedAndClearable() throws Exception {
        String isolation = read("api", "loader", "SpiderIsolation.java");
        String fileUtil = read("utils", "FileUtil.java");

        assertTrue("Only repeated faults may disable a source", isolation.contains("faults >= THRESHOLD"));
        assertTrue("A fault storm must not write preferences without bound", isolation.contains("if (faults >= THRESHOLD) return;"));
        assertTrue(isolation.contains("entry.getKey().startsWith(PREFIX)"));
        assertTrue("Clearing the cache is the recovery entry", fileUtil.contains("SpiderIsolation.reset();"));
    }

    private static String read(String... parts) throws Exception {
        Path relative = Path.of("com", "fongmi", "android", "tv");
        for (String part : parts) relative = relative.resolve(part);
        return new String(Files.readAllBytes(mainJava().resolve(relative)), StandardCharsets.UTF_8);
    }

    private static Path mainJava() {
        Path moduleRelative = Path.of("src", "main", "java");
        if (Files.exists(moduleRelative)) return moduleRelative;
        return Path.of("app", "src", "main", "java");
    }
}
