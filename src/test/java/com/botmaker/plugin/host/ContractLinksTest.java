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
        // A class directory carries no pom, so which side is behind is not known: the line says what is missing.
        assertEquals(List.of("p.Newer — built for a different Studio: it uses StudioPlugin.newer(…),"
                        + " which this Studio has not got"),
                loaded.failures().stream().map(PluginLoader.PluginFailure::describe).toList());
    }

    // ---- which side is behind ----

    @Test
    void the_direction_is_the_plugins_pin_against_the_hosts() {
        assertEquals(ContractLinks.Direction.OLDER, ContractLinks.direction("v0.2.1", "v0.3.0"));
        assertEquals(ContractLinks.Direction.NEWER, ContractLinks.direction("v0.4.0", "v0.3.0"));
        assertEquals(ContractLinks.Direction.NEWER, ContractLinks.direction("v0.10.0", "v0.9.3"));
        assertEquals(ContractLinks.Direction.DIFFERENT, ContractLinks.direction(null, "v0.3.0"));
        assertEquals(ContractLinks.Direction.DIFFERENT, ContractLinks.direction("v0.3.0", "v0.3.0"));
        // A development host is main, at or past every tag.
        assertEquals(ContractLinks.Direction.OLDER, ContractLinks.direction("v0.2.1", null));
    }

    @Test
    void a_jars_flattened_pom_names_its_contract(@TempDir Path dir) throws IOException {
        Path tagged = jar(dir.resolve("tagged.jar"), Map.of("META-INF/maven/g/a/pom.xml", pom("v0.2.1")));
        Path snapshot = jar(dir.resolve("snapshot.jar"), Map.of("META-INF/maven/g/a/pom.xml", pom("0.0.0-SNAPSHOT")));
        Path none = jar(dir.resolve("none.jar"), Map.of("x.txt", "x"));

        assertEquals("v0.2.1", ContractLinks.declaredContract(tagged));
        assertNull(ContractLinks.declaredContract(snapshot));
        assertNull(ContractLinks.declaredContract(none));
    }

    /**
     * The user's case of 2026-09-29: an SDK whose plugin extends a contract type Studio has since deleted. The
     * provider cannot even be resolved, so before the fix the line was {@code a plugin — com/…/Gone is not on
     * the classpath}, naming neither the plugin nor the way out.
     */
    @Test
    void a_plugin_built_on_a_deleted_contract_type_is_named_and_called_older(@TempDir Path dir) throws IOException {
        Path stub = compile(dir.resolve("stub"), contractPath(), Map.of(
                "com/botmaker/plugin/api/Gone.java",
                "package com.botmaker.plugin.api; public abstract class Gone implements StudioPlugin { }"));
        Path classes = compile(dir.resolve("plugin"), stub + java.io.File.pathSeparator + contractPath(), Map.of(
                "p/Old.java",
                "package p; public final class Old extends com.botmaker.plugin.api.Gone {"
                        + " public String id() { return \"test.old\"; } }"));
        Map<String, String> entries = new java.util.HashMap<>();
        entries.put("p/Old.class", Files.readString(classes.resolve("p/Old.class"), java.nio.charset.StandardCharsets.ISO_8859_1));
        entries.put(ContractLinks.SERVICES, "p.Old\n");
        entries.put("META-INF/maven/g/old/pom.xml", pom("v0.0.1"));
        Path jar = jar(dir.resolve("old.jar"), entries);

        PluginLoader.Loaded loaded = PluginLoader.openReporting(List.of(jar.toString()));

        assertNull(loaded.loader());
        assertEquals(1, loaded.failures().size(), loaded.failures().toString());
        PluginLoader.PluginFailure failure = loaded.failures().getFirst();
        assertEquals("p.Old", failure.provider());
        ContractLinks.ContractMismatch mismatch = (ContractLinks.ContractMismatch) failure.cause();
        assertEquals(ContractLinks.Direction.OLDER, mismatch.direction());
        assertEquals("v0.0.1", mismatch.pluginContract());
        assertTrue(failure.describe().startsWith("p.Old — built for an older Studio (contract v0.0.1); update the plugin."),
                failure.describe());
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
        // A fixture that stops compiling is this test failing, never a reason to skip it.
        assertEquals(0, javac.run(null, null, null, args.toArray(String[]::new)), "the fixture compiles");
        return classes;
    }

    private static String pom(String contract) {
        return "<project><dependencies><dependency><groupId>com.github.BotMakerDev</groupId>"
                + "<artifactId>botmaker-studio-api</artifactId>\n      <version>" + contract
                + "</version></dependency></dependencies></project>";
    }

    /** A jar of {@code entries}; each value's chars are written as bytes one-for-one (ISO-8859-1). */
    private static Path jar(Path file, Map<String, String> entries) throws IOException {
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                out.closeEntry();
            }
        }
        return file;
    }

    private static String contractPath() {
        return StudioPlugin.class.getProtectionDomain().getCodeSource().getLocation().getPath();
    }
}
