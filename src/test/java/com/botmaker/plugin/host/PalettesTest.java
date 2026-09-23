package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A plugin's palette is the {@code @Palette} classes in its own jar, found by the host and loaded the way
 * {@link PluginLoader} loads the plugin.
 */
class PalettesTest {

    @Test
    void the_annotated_classes_in_the_plugins_jar_are_its_palette(@TempDir Path dir) throws IOException {
        Path jar = jar(compile(dir, "PaletteCatalog.empty()"), dir.resolve("plugin.jar"));
        try (PluginLoader loader = PluginLoader.open(List.of(jar.toString()))) {
            assertNotNull(loader);
            PaletteCatalog palette = Palettes.of(loader.plugins().getFirst());
            assertEquals(List.of(), palette.problems());
            assertEquals(List.of("p.Api", "p.Other"), names(palette), "p.Plain carries no @Palette");
            assertEquals(List.of("greet"), palette.facades().getFirst().members().stream()
                    .map(m -> m.id().name()).toList());
        }
    }

    @Test
    void a_class_directory_is_read_the_same_way(@TempDir Path dir) throws IOException {
        Path classes = compile(dir, "PaletteCatalog.empty()");
        try (PluginLoader loader = PluginLoader.open(List.of(classes.toString()))) {
            assertNotNull(loader);
            assertEquals(List.of("p.Api", "p.Other"), names(Palettes.of(loader.plugins().getFirst())));
        }
    }

    /** A plugin that builds its catalog by hand keeps it; nothing is discovered on top. */
    @Test
    void a_hand_built_catalog_wins(@TempDir Path dir) throws IOException {
        Path classes = compile(dir, "PaletteCatalog.of(p.Other.class)");
        try (PluginLoader loader = PluginLoader.open(List.of(classes.toString()))) {
            assertNotNull(loader);
            assertEquals(List.of("p.Other"), names(Palettes.of(loader.plugins().getFirst())));
        }
    }

    private static List<String> names(PaletteCatalog palette) {
        return palette.facades().stream().map(FacadeEntry::qualifiedName).toList();
    }

    /**
     * {@code p.Api} and {@code p.Other} carry {@code @Palette}, {@code p.Plain} does not, and the plugin's
     * {@code catalog()} answers {@code catalogExpression}.
     */
    private static Path compile(Path dir, String catalogExpression) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "no javac in this JRE");

        Path src = Files.createDirectories(dir.resolve("src/p"));
        String palette = "@com.botmaker.plugin.api.palette.Palette(category = \"demo\", order = %d)";
        Files.writeString(src.resolve("Api.java"), "package p; " + palette.formatted(1)
                + " public final class Api { public static String greet(String who) { return who; } }");
        Files.writeString(src.resolve("Other.java"), "package p; " + palette.formatted(2)
                + " public final class Other { public static void wave() { } }");
        Files.writeString(src.resolve("Plain.java"), "package p; public final class Plain { }");
        Files.writeString(src.resolve("ThePlugin.java"), """
                package p;
                import com.botmaker.plugin.api.catalog.PaletteCatalog;
                public final class ThePlugin implements com.botmaker.plugin.api.StudioPlugin {
                    @Override public String id() { return "test.palette"; }
                    @Override public PaletteCatalog catalog() { return %s; }
                }
                """.formatted(catalogExpression));

        Path classes = Files.createDirectories(dir.resolve("classes"));
        String contract = StudioPlugin.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        List<String> args = new java.util.ArrayList<>(List.of("-cp", contract, "-d", classes.toString()));
        try (Stream<Path> files = Files.list(src)) {
            files.map(Path::toString).forEach(args::add);
        }
        assumeTrue(javac.run(null, null, null, args.toArray(String[]::new)) == 0, "could not compile the fixture");

        Path services = Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(services.resolve(StudioPlugin.class.getName()), "p.ThePlugin\n");
        return classes;
    }

    private static Path jar(Path classes, Path jar) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }
}
