package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A plugin built against a newer contract is refused at load, naming what it needs; one built against this
 * contract loads.
 *
 * <p>The newer contract is a stub compiled here: the plugin's classes are built against it, then read against
 * the real one, which is exactly the state of a jar built for a later Studio.
 */
class ContractLinksTest {

    private static final ClassLoader CONTRACT = StudioPlugin.class.getClassLoader();

    @Test
    void a_member_this_contract_lacks_is_missing(@TempDir Path dir) throws IOException {
        Path classes = compileAgainstStub(dir, Map.of(
                "com/botmaker/plugin/api/StudioPlugin.java",
                "package com.botmaker.plugin.api; public interface StudioPlugin {"
                        + " String id(); default void newer() {} }"),
                Map.of("p/Newer.java",
                        "package p; public final class Newer implements com.botmaker.plugin.api.StudioPlugin {"
                                + " public String id() { newer(); return \"test.newer\"; } }"));

        List<ContractLinks.Link> missing = ContractLinks.missing(classes, CONTRACT);

        assertEquals(List.of("StudioPlugin.newer(…)"), missing.stream().map(ContractLinks.Link::describe).toList());
    }

    @Test
    void a_class_this_contract_lacks_is_missing(@TempDir Path dir) throws IOException {
        Path classes = compileAgainstStub(dir, Map.of(
                "com/botmaker/plugin/api/Later.java",
                "package com.botmaker.plugin.api; public final class Later { public static int soon() { return 1; } }"),
                Map.of("p/UsesLater.java",
                        "package p; public final class UsesLater {"
                                + " int use() { return com.botmaker.plugin.api.Later.soon(); } }"));

        List<String> missing = ContractLinks.missing(classes, CONTRACT).stream()
                .map(ContractLinks.Link::describe).toList();

        assertTrue(missing.contains("Later"), missing.toString());
        assertTrue(missing.contains("Later.soon(…)"), missing.toString());
    }

    /**
     * The trap the steps close: a record's public constructor, made private when a step builder replaced it.
     * A plugin compiled against the record links a constructor that still exists and may not be called.
     */
    @Test
    void a_constructor_this_contract_hides_is_missing(@TempDir Path dir) throws IOException {
        Path classes = compileAgainstStub(dir, Map.of(
                "com/botmaker/plugin/api/source/ManagedValue.java",
                "package com.botmaker.plugin.api.source; public final class ManagedValue<T> {"
                        + " public ManagedValue(String id, String reason, String holder, Class<T> type, T initial) {} }"),
                Map.of("p/Builds.java",
                        "package p; public final class Builds { Object make() { return new"
                                + " com.botmaker.plugin.api.source.ManagedValue<>(\"a\", \"b\", null, String.class, \"\"); } }"));

        List<String> missing = ContractLinks.missing(classes, CONTRACT).stream()
                .map(ContractLinks.Link::describe).toList();

        assertEquals(List.of("ManagedValue(…)"), missing);
    }

    @Test
    void a_plugin_built_against_this_contract_links_nothing_missing(@TempDir Path dir) throws IOException {
        Path classes = compile(dir, contractPath(), Map.of("p/Current.java",
                "package p; import com.botmaker.plugin.api.toolbar.*;"
                        + " public final class Current implements com.botmaker.plugin.api.StudioPlugin {"
                        + " public String id() { return \"test.current\"; }"
                        + " ToolbarItem item() { return ToolbarItem.id(\"x\").label(\"x\").tooltip(\"y\")"
                        + " .in(ToolbarGroup.TOOLS, 1).enabledWhen(EnabledWhen.ALWAYS).onPress(() -> c -> { }); }"
                        + " String name() { return ToolbarGroup.TOOLS.name() + id().hashCode(); } }"));

        assertEquals(List.of(), ContractLinks.missing(classes, CONTRACT));
    }

    @Test
    void the_loader_refuses_the_newer_plugin_and_says_what_it_needs(@TempDir Path dir) throws IOException {
        Path classes = compileAgainstStub(dir, Map.of(
                "com/botmaker/plugin/api/StudioPlugin.java",
                "package com.botmaker.plugin.api; public interface StudioPlugin {"
                        + " String id(); default void newer() {} }"),
                Map.of("p/Newer.java",
                        "package p; public final class Newer implements com.botmaker.plugin.api.StudioPlugin {"
                                + " public String id() { newer(); return \"test.newer\"; } }"));
        Path services = Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(services.resolve("com.botmaker.plugin.api.StudioPlugin"), "p.Newer\n");

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(classes.toString()));

        assertNull(loaded.loader());
        assertEquals(List.of("p.Newer — built for a newer Studio: needs StudioPlugin.newer(…)"),
                loaded.failures().stream().map(PluginLoader.PluginFailure::describe).toList());
    }

    // ---- fixtures ----

    /** Compiles {@code stub} alone, then {@code plugin} against it, and answers the plugin's classes. */
    private static Path compileAgainstStub(Path dir, Map<String, String> stub, Map<String, String> plugin)
            throws IOException {
        Path stubClasses = compile(dir.resolve("stub"), null, stub);
        return compile(dir.resolve("plugin"), stubClasses.toString(), plugin);
    }

    private static Path compile(Path dir, String classpath, Map<String, String> sources) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "no javac in this JRE");
        List<String> args = new ArrayList<>();
        if (classpath != null) args.addAll(List.of("-cp", classpath));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        args.addAll(List.of("-d", classes.toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = dir.resolve("src").resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assumeTrue(javac.run(null, null, null, args.toArray(String[]::new)) == 0, "could not compile the fixture");
        return classes;
    }

    private static String contractPath() {
        return StudioPlugin.class.getProtectionDomain().getCodeSource().getLocation().getPath();
    }
}
