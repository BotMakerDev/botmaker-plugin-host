package com.botmaker.plugin.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.botmaker.plugin.host.PluginFixtures.bytes;
import static com.botmaker.plugin.host.PluginFixtures.compile;
import static com.botmaker.plugin.host.PluginFixtures.contractPath;
import static com.botmaker.plugin.host.PluginFixtures.declare;
import static com.botmaker.plugin.host.PluginFixtures.jar;
import static com.botmaker.plugin.host.PluginFixtures.plugin;
import static com.botmaker.plugin.host.PluginFixtures.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a host is entitled to assume of the loader.
 *
 * <p>The halves worth holding are the ones with no visible symptom when they are wrong: the parent-first rule,
 * whose failure surfaces much later as a {@link ClassCastException} between two identically named classes; and
 * one jar being one plugin, whose failure is two plugins answering for the same classes.
 */
class PluginLoaderTest {

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
        assertFalse(PluginLoader.parentFirst("com.botmaker.sdk.api.input.Mouse"));
    }

    @Test
    void a_plugin_package_that_merely_starts_like_the_contract_is_not_parent_first() {
        // The prefixes end in a dot on purpose. Without it com.botmaker.plugin.apix.* — or a third-party
        // javafxsupport.* — would be silently taken from the host.
        assertFalse(PluginLoader.parentFirst("com.botmaker.plugin.apix.Thing"));
        assertFalse(PluginLoader.parentFirst("com.botmaker.plugin.host.PluginLoader"));
        assertFalse(PluginLoader.parentFirst("javafxsupport.Thing"));
    }

    // ---- nothing to load answers null ----

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
    void a_library_is_not_a_plugin(@TempDir Path dir) throws IOException {
        // The ordinary case for a project that pins a library: its pom names no class.
        Path classes = compile(dir, contractPath(), Map.ofEntries(plugin("Good")));
        Path pom = classes.resolve("META-INF/maven/test/lib/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, pom("test", "lib", null, null));

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(classes.toString()));

        assertNull(loaded.loader());
        assertEquals(List.of(), loaded.failures());
    }

    // ---- the round trip ----

    @Test
    void the_class_the_pom_names_is_loaded_with_its_coordinate_name_and_jar(@TempDir Path dir) throws IOException {
        Path classes = declare(compile(dir, contractPath(), Map.ofEntries(plugin("Good"))), "good", "p.Good");

        try (PluginLoader loader = PluginLoader.open(List.of(classes.toString()))) {
            assertNotNull(loader);
            LoadedPlugin only = loader.plugins().getFirst();
            assertEquals(1, loader.plugins().size());
            assertEquals(new PluginCoordinate("test", "good"), only.coordinate());
            assertEquals("good plugin", only.name());
            assertEquals(classes.toAbsolutePath().normalize(), only.jar());
            assertEquals("p.Good", only.plugin().getClass().getName());
        }
    }

    @Test
    void a_plugin_whose_own_dependency_is_missing_says_which(@TempDir Path dir) throws IOException {
        // Found on 2026-08-28 by loading the archetype's skeleton with the toolkit left off the classpath:
        // resolving the class throws NoClassDefFoundError — an Error, not a RuntimeException.
        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(brokenPlugin(dir).toString()));

        assertNull(loaded.loader());
        assertEquals(List.of("p.BrokenPlugin — p/Helper is not on the classpath"),
                loaded.failures().stream().map(PluginLoader.PluginFailure::describe).toList());
    }

    // ---- one broken plugin costs only itself ----

    /** The broken one comes first on purpose — the ordering the services-file loop once lost the others on. */
    @Test
    @Timeout(30)
    void a_broken_plugin_does_not_cost_the_others(@TempDir Path dir) throws IOException {
        Path broken = brokenPlugin(dir.resolve("broken"));
        Path good = declare(compile(dir.resolve("good"), contractPath(), Map.ofEntries(plugin("Good"))),
                "good", "p.Good");

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(broken.toString(), good.toString()));
        try (PluginLoader plugins = loaded.loader()) {
            assertNotNull(plugins, "the working plugin must survive the broken one");
            assertEquals(List.of("test:good"), coordinates(plugins));
            assertEquals(1, loaded.failures().size(), "the broken one must be reported, not swallowed");
        }
    }

    /** A constructor that throws: the shape SDK v1.1.5 shipped, a constructor linking an optional dependency. */
    @Test
    @Timeout(30)
    void a_plugin_whose_constructor_throws_is_reported_by_what_it_threw(@TempDir Path dir) throws IOException {
        Path classes = declare(compile(dir, contractPath(), Map.of("p/ThrowingPlugin.java",
                "package p; import com.botmaker.plugin.api.*; public final class ThrowingPlugin extends DeclaredPlugin {"
                        + " public ThrowingPlugin() { super(StudioPlugin.declare());"
                        + " if (true) throw new IllegalStateException(\"no display\"); } }")),
                "throwing", "p.ThrowingPlugin");

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(classes.toString()));

        assertNull(loaded.loader());
        assertEquals(List.of("p.ThrowingPlugin — no display"),
                loaded.failures().stream().map(PluginLoader.PluginFailure::describe).toList());
    }

    // ---- one jar, one plugin ----

    @Test
    void a_jar_whose_poms_name_two_plugins_is_refused_whole(@TempDir Path dir) throws IOException {
        Path classes = compile(dir, contractPath(), Map.ofEntries(plugin("One"), plugin("Two")));
        Path shaded = jar(dir.resolve("shaded.jar"), Map.of(
                "p/One.class", bytes(classes.resolve("p/One.class")),
                "p/Two.class", bytes(classes.resolve("p/Two.class")),
                "META-INF/maven/test/one/pom.xml", pom("test", "one", "p.One", null),
                "META-INF/maven/test/two/pom.xml", pom("test", "two", "p.Two", null)));

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(shaded.toString()));

        assertNull(loaded.loader());
        assertEquals(1, loaded.failures().size());
        String line = loaded.failures().getFirst().describe();
        assertTrue(line.startsWith("shaded.jar — its poms name 2 plugins ("), line);
        assertTrue(line.endsWith("one jar is one plugin, so none of them is loaded"), line);
    }

    @Test
    void a_second_jar_with_the_same_coordinate_is_refused(@TempDir Path dir) throws IOException {
        Path first = declare(compile(dir.resolve("a"), contractPath(), Map.ofEntries(plugin("One"))), "same", "p.One");
        Path second = declare(compile(dir.resolve("b"), contractPath(), Map.ofEntries(plugin("Two"))), "same", "p.Two");

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(first.toString(), second.toString()));
        try (PluginLoader plugins = loaded.loader()) {
            assertNotNull(plugins);
            assertEquals(List.of("p.One"), plugins.plugins().stream()
                    .map(p -> p.plugin().getClass().getName()).toList());
            assertEquals(1, loaded.failures().size());
            assertEquals("p.Two", loaded.failures().getFirst().provider());
            assertTrue(loaded.failures().getFirst().describe().contains("is test:same"),
                    loaded.failures().getFirst().describe());
        }
    }

    /** A coordinate is claimed only by an entry that loads: a refused first copy leaves room for a good second. */
    @Test
    void a_copy_refused_for_its_contract_does_not_shadow_a_good_one(@TempDir Path dir) throws IOException {
        Path stub = compile(dir.resolve("stub"), null, Map.of("com/botmaker/plugin/api/Later.java",
                "package com.botmaker.plugin.api; public final class Later { public static int soon() { return 1; } }"));
        Path newer = compile(dir.resolve("newer"), stub + java.io.File.pathSeparator + contractPath(), Map.of(
                "p/One.java", "package p; import com.botmaker.plugin.api.*; public final class One extends DeclaredPlugin {"
                        + " public One() { super(StudioPlugin.declare()); Later.soon(); } }"));
        Path good = compile(dir.resolve("good"), contractPath(), Map.ofEntries(plugin("Two")));

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(
                declare(newer, "same", "p.One").toString(), declare(good, "same", "p.Two").toString()));
        try (PluginLoader plugins = loaded.loader()) {
            assertNotNull(plugins);
            assertEquals(List.of("p.Two"), plugins.plugins().stream()
                    .map(p -> p.plugin().getClass().getName()).toList());
            assertEquals(List.of("p.One"), loaded.failures().stream().map(PluginLoader.PluginFailure::provider).toList());
        }
    }

    @Test
    void an_entry_listed_twice_is_one_plugin(@TempDir Path dir) throws IOException {
        Path classes = declare(compile(dir, contractPath(), Map.ofEntries(plugin("Good"))), "good", "p.Good");

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(classes.toString(), classes + "/."));
        try (PluginLoader plugins = loaded.loader()) {
            assertEquals(1, plugins.plugins().size());
            assertEquals(List.of(), loaded.failures());
        }
    }

    @Test
    void a_pom_that_names_a_plugin_but_cannot_be_used_is_reported(@TempDir Path dir) throws IOException {
        Path broken = jar(dir.resolve("broken.jar"), Map.of(
                "META-INF/maven/test/broken/pom.xml", pom("test", "broken", "${nowhere}", null)));

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(broken.toString()));

        assertNull(loaded.loader());
        assertEquals(List.of("broken.jar — broken.jar!/META-INF/maven/test/broken/pom.xml's botmaker.plugin is"
                        + " ${nowhere}, which nothing resolved: write the class's name itself"),
                loaded.failures().stream().map(PluginLoader.PluginFailure::describe).toList());
    }

    @Test
    void the_named_class_must_be_a_public_final_direct_declared_plugin(@TempDir Path dir) throws IOException {
        Path classes = compile(dir, contractPath(), Map.of(
                "p/Plain.java", "package p; public final class Plain { }",
                "p/Open.java", "package p; import com.botmaker.plugin.api.*; public class Open extends DeclaredPlugin {"
                        + " public Open() { super(StudioPlugin.declare()); } }"));
        Path plain = Files.createDirectories(dir.resolve("plain"));
        Path open = Files.createDirectories(dir.resolve("open"));
        Path plainJar = jar(plain.resolve("plain.jar"), Map.of("p/Plain.class", bytes(classes.resolve("p/Plain.class")),
                "META-INF/maven/test/plain/pom.xml", pom("test", "plain", "p.Plain", null)));
        Path openJar = jar(open.resolve("open.jar"), Map.of("p/Open.class", bytes(classes.resolve("p/Open.class")),
                "META-INF/maven/test/open/pom.xml", pom("test", "open", "p.Open", null)));

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(plainJar.toString(), openJar.toString()));

        assertNull(loaded.loader());
        assertEquals(List.of("p.Plain — p.Plain does not extend DeclaredPlugin",
                        "p.Open — p.Open is not a public final class"),
                loaded.failures().stream().map(PluginLoader.PluginFailure::describe).toList());
    }

    @Test
    void the_named_class_must_be_in_the_jar_that_names_it(@TempDir Path dir) throws IOException {
        Path elsewhere = compile(dir.resolve("elsewhere"), contractPath(), Map.ofEntries(plugin("Good")));
        Path naming = jar(dir.resolve("naming.jar"), Map.of(
                "META-INF/maven/test/naming/pom.xml", pom("test", "naming", "p.Good", null)));
        Path missing = jar(dir.resolve("missing.jar"), Map.of(
                "META-INF/maven/test/missing/pom.xml", pom("test", "missing", "p.Nowhere", null)));

        PluginLoader.Loaded loaded = PluginLoader.openReporting(
                List.of(naming.toString(), missing.toString(), elsewhere.toString()));

        assertNull(loaded.loader());
        assertEquals(List.of("p.Good — the pom's botmaker.plugin names p.Good, which is not in naming.jar:"
                        + " a plugin's class is in its own jar",
                        "p.Nowhere — the pom's botmaker.plugin names p.Nowhere, which is not in missing.jar"),
                loaded.failures().stream().map(PluginLoader.PluginFailure::describe).toList());
    }

    @Test
    void a_missing_classpath_entry_does_not_lose_the_rest(@TempDir Path dir) throws IOException {
        Path classes = declare(compile(dir, contractPath(), Map.ofEntries(plugin("Good"))), "good", "p.Good");

        try (PluginLoader loaded = PluginLoader.open(
                List.of("", dir.resolve("gone.jar").toString(), classes.toString()))) {
            assertNotNull(loaded);
            assertEquals(1, loaded.plugins().size());
        }
    }

    /**
     * A plugin compiled against a superclass whose {@code .class} is then deleted — exactly the state a jar
     * resolved without its own dependency is in. The helper is a SUPERCLASS: a missing class named only inside a
     * method body resolves lazily and would not reproduce it.
     */
    private static Path brokenPlugin(Path dir) throws IOException {
        Path classes = compile(dir, contractPath(), Map.of(
                "p/Helper.java", "package p; import com.botmaker.plugin.api.*;"
                        + " public abstract class Helper extends DeclaredPlugin {"
                        + " protected Helper() { super(StudioPlugin.declare()); } }",
                "p/BrokenPlugin.java", "package p; public final class BrokenPlugin extends Helper { }"));
        Files.delete(classes.resolve("p/Helper.class"));
        return declare(classes, "broken", "p.BrokenPlugin");
    }

    private static List<String> coordinates(PluginLoader loader) {
        return loader.plugins().stream().map(p -> p.coordinate().toString()).toList();
    }
}
