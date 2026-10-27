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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A value the bot fills in is declared by its {@code @Fills} method, found in the plugin's own class files; a
 * method of the wrong shape, or a second one for a class, declares nothing and says why.
 */
class FilledTypesTest {

    @Test
    void each_fills_method_declares_its_return_type(@TempDir Path dir) throws IOException {
        try (PluginLoader loader = PluginLoader.open(List.of(compile(dir).toString()))) {
            assertNotNull(loader);
            FilledTypes.Found found = FilledTypes.of(loader.plugins().getFirst().plugin());
            assertEquals(List.of("p.Match: Picture match <- last", "p.Score: Score <- zero"), found.filled().stream()
                    .map(f -> f.type().getName() + ": " + f.label() + " <- " + f.call().getName()).toList());
            assertEquals(3, found.problems().size(), found.problems().toString());
            assertTrue(found.problems().stream().anyMatch(p -> p.contains("p.Api.other()") && p.contains("already")));
            assertTrue(found.problems().stream().anyMatch(p -> p.contains("p.Api.mine()") && p.contains("public static")));
            assertTrue(found.problems().stream().anyMatch(p -> p.contains("p.Api.of(") && p.contains("parameters")));
        }
    }

    private static Path compile(Path dir) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "no javac in this JRE");

        Path src = Files.createDirectories(dir.resolve("src/p"));
        String fills = "@com.botmaker.plugin.api.palette.Fills";
        Files.writeString(src.resolve("Match.java"), "package p; public record Match(int x) { }");
        Files.writeString(src.resolve("Score.java"), "package p; public record Score(int value) { }");
        Files.writeString(src.resolve("Api.java"), "package p; public final class Api {"
                + fills + "(label = \"Picture match\") public static Match last() { return null; }"
                + fills + " public static Match other() { return null; }"
                + fills + " public Match mine() { return null; }"
                + fills + " public static Match of(int x) { return null; }"
                + fills + " public static Score zero() { return null; }"
                + "}");
        Files.writeString(src.resolve("ThePlugin.java"), """
                package p;
                import com.botmaker.plugin.api.*;
                public final class ThePlugin extends DeclaredPlugin {
                    public ThePlugin() { super(StudioPlugin.declare()); }
                }
                """);

        Path classes = Files.createDirectories(dir.resolve("classes"));
        String contract = StudioPlugin.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        List<String> args = new ArrayList<>(List.of("-cp", contract, "-d", classes.toString()));
        try (Stream<Path> files = Files.list(src)) {
            files.map(Path::toString).forEach(args::add);
        }
        // A fixture that stops compiling is this test failing, never a reason to skip it.
        assertEquals(0, javac.run(null, null, null, args.toArray(String[]::new)), "the fixture compiles");

        return PluginFixtures.declare(classes, "fills", "p.ThePlugin");
    }
}
