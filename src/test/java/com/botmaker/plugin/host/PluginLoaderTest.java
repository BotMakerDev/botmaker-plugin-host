package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a host is entitled to assume of the loader.
 *
 * <p>The two halves worth holding are the ones with no visible symptom when they are wrong: the
 * parent-first rule, whose failure surfaces much later as a {@link ClassCastException} between two
 * identically named classes, and <em>nothing loadable answers null</em>, whose failure is a project opening
 * with no menus at all rather than the bundled ones.
 */
class PluginLoaderTest {

    /** Named by the services file written in {@link #a_services_file_on_the_classpath_is_loaded}. */
    public static final class Stub implements StudioPlugin {
        @Override
        public String id() {
            return "test.stub";
        }
    }

    // ---- the delegation split ----

    @Test
    void the_contract_is_answered_by_the_parent() {
        assertTrue(PluginLoader.parentFirst("com.botmaker.plugin.api.StudioPlugin"));
        assertTrue(PluginLoader.parentFirst("com.botmaker.plugin.api.value.ValueType"));
    }

    @Test
    void the_platform_namespaces_are_answered_by_the_parent() {
        for (String name : List.of("java.util.List", "javax.swing.JFrame", "javafx.scene.Node",
                "jdk.internal.misc.Unsafe", "sun.misc.Unsafe")) {
            assertTrue(PluginLoader.parentFirst(name), name);
        }
    }

    @Test
    void the_sdk_is_answered_by_the_project_first() {
        // The whole reason the split exists: a bot's palette must come from the SDK IT pins, not from the
        // one the host was compiled against.
        assertFalse(PluginLoader.parentFirst("com.botmaker.sdk.plugin.SdkPlugin"));
        assertFalse(PluginLoader.parentFirst("com.botmaker.sdk.api.interaction.Mouse"));
    }

    @Test
    void a_plugin_package_that_merely_starts_like_the_contract_is_not_parent_first() {
        // The prefixes end in a dot on purpose. Without it com.botmaker.plugin.apix.* — or a third-party
        // javafxsupport.* — would be silently taken from the host.
        assertFalse(PluginLoader.parentFirst("com.botmaker.plugin.apix.Thing"));
        assertFalse(PluginLoader.parentFirst("com.botmaker.plugin.host.PluginLoader"));
        assertFalse(PluginLoader.parentFirst("javafxsupport.Thing"));
    }

    // ---- nothing loadable answers null ----

    @Test
    void no_classpath_is_null() {
        assertNull(PluginLoader.open(null));
        assertNull(PluginLoader.open(List.of()));
    }

    @Test
    void a_classpath_of_nothing_but_blanks_is_null() {
        assertNull(PluginLoader.open(List.of("", "   ")));
    }

    @Test
    void a_classpath_with_no_services_file_is_null(@TempDir Path dir) {
        // The ordinary case for a project that pins a library which is not a plugin.
        assertNull(PluginLoader.open(List.of(dir.toString())));
    }

    // ---- the round trip ----

    @Test
    void a_services_file_on_the_classpath_is_loaded(@TempDir Path dir) throws IOException {
        // The class itself is on the parent, and the child directory has no copy — which is exactly the
        // fallback arm of the child-first branch, so this exercises the split rather than working around
        // it. What is being asserted is the ServiceLoader pass: a declaration on the project's own
        // classpath produces an instance typed as the contract.
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve("com.botmaker.plugin.api.StudioPlugin"),
                Stub.class.getName() + "\n");

        try (PluginLoader loaded = PluginLoader.open(List.of(dir.toString()))) {
            assertNotNull(loaded);
            assertEquals(1, loaded.plugins().size());
            assertEquals("test.stub", loaded.plugins().get(0).id());
        }
    }

    @Test
    void a_plugin_whose_own_dependency_is_missing_answers_null(@TempDir Path dir) throws IOException {
        // Found on 2026-08-28 by loading the archetype's skeleton with the toolkit left off the classpath.
        // ServiceLoader's Class.forName throws NoClassDefFoundError — an Error, not a RuntimeException — so
        // before the LinkageError arm it left `open` and aborted the host's project-open.
        //
        // Built rather than described: a plugin is compiled against a helper, the helper's .class is then
        // deleted, which is exactly the state a jar resolved without its dependency is in.
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "no javac in this JRE");

        Path src = Files.createDirectories(dir.resolve("src/p"));
        // The helper is a SUPERCLASS, which is what the real case looks like: the archetype's skeleton
        // extends the toolkit's AbstractStudioPlugin. A missing class named only inside a method body would
        // not reproduce this — the JVM resolves those lazily, so the plugin would load and fail later.
        Files.writeString(src.resolve("Helper.java"),
                "package p; public abstract class Helper { public String name() { return \"broken\"; } }");
        Files.writeString(src.resolve("BrokenPlugin.java"),
                "package p; public final class BrokenPlugin extends Helper"
                        + " implements com.botmaker.plugin.api.StudioPlugin {"
                        + " @Override public String id() { return name(); } }");

        Path classes = Files.createDirectories(dir.resolve("classes"));
        String contract = StudioPlugin.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        int status = javac.run(null, null, null,
                "-cp", contract, "-d", classes.toString(),
                src.resolve("Helper.java").toString(), src.resolve("BrokenPlugin.java").toString());
        assumeTrue(status == 0, "could not compile the fixture");

        Files.delete(classes.resolve("p/Helper.class"));
        Path services = Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(services.resolve("com.botmaker.plugin.api.StudioPlugin"), "p.BrokenPlugin\n");

        assertNull(PluginLoader.open(List.of(classes.toString())));
    }

    // ---- one broken plugin costs only itself ----

    /**
     * <b>The phase-4 defect, and it was invisible while the world had one plugin.</b> The loop used to be one
     * {@code for} over {@link java.util.ServiceLoader} inside one {@code try}: the first provider that would
     * not load ended the iteration, so every plugin declared after it was silently absent. With two plugins
     * on a classpath that is the difference between <i>the SDK is broken</i> and <i>nothing works</i>.
     *
     * <p>The broken one is declared <b>first</b> on purpose — that is the ordering the old code lost the
     * others on.
     */
    @Test
    @Timeout(30)
    void a_broken_plugin_does_not_cost_the_others(@TempDir Path dir) throws IOException {
        Path classes = compileBrokenPlugin(dir);
        Files.writeString(classes.resolve("META-INF/services/com.botmaker.plugin.api.StudioPlugin"),
                "p.BrokenPlugin\n" + Stub.class.getName() + "\n");

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(classes.toString()));
        try (PluginLoader plugins = loaded.loader()) {
            assertNotNull(plugins, "the working plugin must survive the broken one");
            assertEquals(List.of("test.stub"), plugins.plugins().stream().map(p -> p.id()).toList());
            assertEquals(1, loaded.failures().size(), "the broken one must be reported, not swallowed");
            // A missing superclass is a raw NoClassDefFoundError out of Class.forName, which names the
            // class that was missing and not the provider — ServiceLoader never says which line it was
            // on. What the user needs to fix is the missing class, so that is what the line names.
            assertEquals("a plugin — p/Helper is not on the classpath", loaded.failures().get(0).describe());
        }
    }

    /**
     * The second moment a plugin can fail, and it is a different moment: advancing the iterator runs
     * {@code Class.forName}, {@code Provider.get()} runs the no-arg constructor. This is the shape that
     * shipped as SDK v1.1.5 — a constructor that linked an {@code optional} dependency, so every host
     * without it answered {@code NoClassDefFoundError} while {@code ServiceLoader} was constructing.
     */
    @Test
    @Timeout(30)
    void a_plugin_whose_constructor_throws_is_reported_and_the_rest_load(@TempDir Path dir)
            throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "no javac in this JRE");

        Path src = Files.createDirectories(dir.resolve("src/p"));
        Files.writeString(src.resolve("ThrowingPlugin.java"),
                "package p; public final class ThrowingPlugin"
                        + " implements com.botmaker.plugin.api.StudioPlugin {"
                        + " public ThrowingPlugin() { throw new IllegalStateException(\"no display\"); }"
                        + " @Override public String id() { return \"p.throwing\"; } }");

        Path classes = Files.createDirectories(dir.resolve("classes"));
        String contract = StudioPlugin.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        int status = javac.run(null, null, null, "-cp", contract, "-d", classes.toString(),
                src.resolve("ThrowingPlugin.java").toString());
        assumeTrue(status == 0, "could not compile the fixture");

        Path services = Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(services.resolve("com.botmaker.plugin.api.StudioPlugin"),
                "p.ThrowingPlugin\n" + Stub.class.getName() + "\n");

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(classes.toString()));
        try (PluginLoader plugins = loaded.loader()) {
            assertNotNull(plugins);
            assertEquals(List.of("test.stub"), plugins.plugins().stream().map(p -> p.id()).toList());
            assertEquals(1, loaded.failures().size());
            // Here the provider's own type IS known — it loaded, it just would not construct.
            assertEquals("p.ThrowingPlugin", loaded.failures().get(0).provider());
        }
    }

    /** A classpath nothing loads from still says why, which is the half {@code open} drops. */
    @Test
    @Timeout(30)
    void a_classpath_whose_only_plugin_is_broken_answers_no_loader_and_one_failure(@TempDir Path dir)
            throws IOException {
        Path classes = compileBrokenPlugin(dir);
        Files.writeString(classes.resolve("META-INF/services/com.botmaker.plugin.api.StudioPlugin"),
                "p.BrokenPlugin\n");

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(classes.toString()));

        assertNull(loaded.loader(), "no plugin loaded, so the caller must fall back to the bundled set");
        assertEquals(1, loaded.failures().size());
    }

    /**
     * A plugin compiled against a superclass whose {@code .class} is then deleted — exactly the state a jar
     * resolved without its own dependency is in, and how the 2026-08-28 non-transitive toolkit failed.
     */
    private static Path compileBrokenPlugin(Path dir) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "no javac in this JRE");

        Path src = Files.createDirectories(dir.resolve("src/p"));
        Files.writeString(src.resolve("Helper.java"),
                "package p; public abstract class Helper { public String name() { return \"broken\"; } }");
        Files.writeString(src.resolve("BrokenPlugin.java"),
                "package p; public final class BrokenPlugin extends Helper"
                        + " implements com.botmaker.plugin.api.StudioPlugin {"
                        + " @Override public String id() { return name(); } }");

        Path classes = Files.createDirectories(dir.resolve("classes"));
        String contract = StudioPlugin.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        int status = javac.run(null, null, null, "-cp", contract, "-d", classes.toString(),
                src.resolve("Helper.java").toString(), src.resolve("BrokenPlugin.java").toString());
        assumeTrue(status == 0, "could not compile the fixture");

        Files.delete(classes.resolve("p/Helper.class"));
        Files.createDirectories(classes.resolve("META-INF/services"));
        return classes;
    }

    @Test
    void a_missing_classpath_entry_does_not_lose_the_rest(@TempDir Path dir) throws IOException {
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve("com.botmaker.plugin.api.StudioPlugin"),
                Stub.class.getName() + "\n");

        try (PluginLoader loaded = PluginLoader.open(
                List.of("", dir.resolve("gone.jar").toString(), dir.toString()))) {
            assertNotNull(loaded);
            assertEquals(1, loaded.plugins().size());
        }
    }
}
