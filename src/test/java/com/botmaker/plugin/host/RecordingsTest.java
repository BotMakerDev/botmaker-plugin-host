package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.record.Gesture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A plugin's {@code @Records} methods are found on its {@code @Palette} classes, and each parameter is filled by
 * the first rule that fits.
 */
class RecordingsTest {

    @Test
    void writers_are_found_and_ranked_within_a_gesture(@TempDir Path dir) throws IOException {
        try (PluginLoader loader = PluginLoader.open(List.of(compile(dir).toString()))) {
            assertNotNull(loader);
            List<Recordings.Writer> writers = Recordings.of(loader.plugins());

            assertEquals(List.of("clickThing", "click", "type", "tap", "chord"),
                    writers.stream().map(w -> w.method().getName()).toList(),
                    "CLICK first by gesture order, the ranked picture click before the plain one; mixed fills nothing");
            assertEquals(Gesture.CLICK, writers.getFirst().gesture());
        }
    }

    @Test
    void each_parameter_is_classified_by_the_first_rule_that_fits(@TempDir Path dir) throws IOException {
        try (PluginLoader loader = PluginLoader.open(List.of(compile(dir).toString()))) {
            assertNotNull(loader);
            List<Recordings.Writer> writers = Recordings.of(loader.plugins());

            assertInstanceOf(Recordings.Slot.Recorded.class, writer(writers, "clickThing").slots().getFirst());
            assertInstanceOf(Recordings.Slot.Number.class, writer(writers, "click").slots().getFirst());
            assertInstanceOf(Recordings.Slot.Keys.class, writer(writers, "tap").slots().getFirst());
            assertInstanceOf(Recordings.Slot.Text.class, writer(writers, "type").slots().getFirst());
            Recordings.Slot chord = writer(writers, "chord").slots().getFirst();
            assertInstanceOf(Recordings.Slot.KeyParts.class, chord);
            assertEquals("p.Key", ((Recordings.Slot.KeyParts) chord).enumType().getName());
        }
    }

    @Test
    void a_method_nothing_can_fill_is_a_problem_and_never_a_writer(@TempDir Path dir) throws IOException {
        try (PluginLoader loader = PluginLoader.open(List.of(compile(dir).toString()))) {
            assertNotNull(loader);
            StudioPlugin plugin = loader.plugins().getFirst();

            assertEquals(List.of(
                            "p.Input#hold carries @Records but nothing fills its java.lang.Object parameter",
                            "p.Input#mine carries @Records but is not public static",
                            "p.Input#mixed carries @Records but nothing fills its p.Mixed parameter"),
                    Recordings.problems(plugin, loader.plugins()).stream().sorted().toList());
        }
    }

    private static Recordings.Writer writer(List<Recordings.Writer> writers, String name) {
        return writers.stream().filter(w -> w.method().getName().equals(name)).findFirst().orElseThrow();
    }

    /**
     * {@code p.Input} carries eight {@code @Records} methods; the plugin answers {@code p.Thing} itself, and declares
     * {@code p.Chord} (a varargs call over {@code p.Key}) and {@code p.Mixed} (a key and a count) as components.
     */
    private static Path compile(Path dir) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "no javac in this JRE");

        Path src = Files.createDirectories(dir.resolve("src/p"));
        Files.writeString(src.resolve("Thing.java"), "package p; public final class Thing { }");
        Files.writeString(src.resolve("Key.java"), "package p; public enum Key { ENTER, S }");
        Files.writeString(src.resolve("Chord.java"), "package p; public record Chord(java.util.List<Key> keys) {"
                + " public static Chord of(Key... k) { return new Chord(java.util.List.of(k)); } }");
        Files.writeString(src.resolve("Mixed.java"), "package p; public record Mixed(Key key, int times) { }");
        Files.writeString(src.resolve("Input.java"), """
                package p;
                import com.botmaker.plugin.api.record.Gesture;
                import com.botmaker.plugin.api.record.Records;
                @com.botmaker.plugin.api.palette.Palette(category = "demo")
                public final class Input {
                    @Records(Gesture.CLICK) public static void click(int x, int y) { }
                    @Records(value = Gesture.CLICK, rank = 10) public static void clickThing(Thing t) { }
                    @Records(Gesture.TYPE) public static void type(String text) { }
                    @Records(Gesture.KEY) public static void tap(Key key) { }
                    @Records(value = Gesture.COMBO, rank = 1) public static void chord(Chord c) { }
                    @Records(Gesture.COMBO) public static void mixed(Mixed m) { }
                    @Records(Gesture.PAUSE) public static void hold(Object o) { }
                    @Records(Gesture.PAUSE) public void mine() { }
                }
                """);
        Files.writeString(src.resolve("ThePlugin.java"), """
                package p;
                import com.botmaker.plugin.api.StudioServices;
                import com.botmaker.plugin.api.record.RecordedValue;
                import java.util.List;
                import java.util.Optional;
                public final class ThePlugin implements com.botmaker.plugin.api.StudioPlugin {
                    @Override public String id() { return "test.record"; }
                    @Override public List<com.botmaker.plugin.api.value.ComponentType<?>> componentTypes() {
                        return List.of(new com.botmaker.plugin.api.value.ComponentType<Chord>() {
                            @Override public Class<Chord> type() { return Chord.class; }
                            @Override public java.lang.reflect.Executable factory() {
                                try { return Chord.class.getMethod("of", Key[].class); }
                                catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
                            }
                            @Override public List<Class<?>> componentTypes() { return List.of(Key.class); }
                            @Override public List<Object> components(Chord c) { return List.copyOf(c.keys()); }
                            @Override public Chord build(List<Object> parts) {
                                return new Chord(parts.stream().map(Key.class::cast).toList());
                            }
                        }, new com.botmaker.plugin.api.value.ComponentType<Mixed>() {
                            @Override public Class<Mixed> type() { return Mixed.class; }
                            @Override public List<Class<?>> componentTypes() { return List.of(Key.class, int.class); }
                            @Override public List<Object> components(Mixed m) { return List.of(m.key(), m.times()); }
                            @Override public Mixed build(List<Object> parts) {
                                return new Mixed((Key) parts.get(0), (Integer) parts.get(1));
                            }
                        });
                    }
                    @Override public List<RecordedValue<?>> recordedValues() {
                        return List.of(new RecordedValue<Thing>() {
                            @Override public Class<Thing> type() { return Thing.class; }
                            @Override public Optional<Thing> at(StudioServices s, Spot spot) {
                                return Optional.of(new Thing());
                            }
                        });
                    }
                }
                """);

        Path classes = Files.createDirectories(dir.resolve("classes"));
        String contract = StudioPlugin.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        List<String> args = new java.util.ArrayList<>(List.of("-cp", contract, "-d", classes.toString()));
        try (Stream<Path> files = Files.list(src)) {
            files.map(Path::toString).forEach(args::add);
        }
        // A fixture that stops compiling is this test failing, never a reason to skip it.
        assertEquals(0, javac.run(null, null, null, args.toArray(String[]::new)), "the fixture compiles");

        Path services = Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(services.resolve(StudioPlugin.class.getName()), "p.ThePlugin\n");
        return classes;
    }
}
