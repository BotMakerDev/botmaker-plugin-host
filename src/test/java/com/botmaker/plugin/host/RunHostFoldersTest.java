// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.plugin.host;

import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.plugin.api.PluginState;
import com.botmaker.plugin.api.source.ManagedMethod;
import com.botmaker.plugin.api.source.ManagedValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A run's half of a plugin's values of this PC (2026-11-15): its own values only, written in place, unchecked. */
class RunHostFoldersTest {

    private static final ManagedMethod<String> DONE = ManagedValue.method(TestRunValue.Id.DONE)
            .in(TestRunValue.Holder.RUNS)
            .holds(String.class, "none")
            .because("What the runs did.");

    private static final String RUNS = """
            package com_github_botmakerdev.my_plugin;

            import com.botmaker.plugin.host.TestRunValue;

            public class Runs {
                @TestRunValue(TestRunValue.Id.DONE)
                public static String done() { return "first"; }
            }
            """;

    /** The plugin's folder: BotMaker's older groupId kept under the current one, as the host keeps it. */
    private static Path folder(Path root) {
        return root.resolve("plugins").resolve(PluginState.part("com.github.BotMakerDev"))
                .resolve(PluginState.part("my-plugin"));
    }

    private static Path seeded(Path root) throws IOException {
        String g = PluginState.part("com.github.BotMakerDev");
        String a = PluginState.part("my-plugin");
        Path file = folder(root).resolve("src/main/java").resolve(g).resolve(a).resolve("Runs.java");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, RUNS);
    }

    private static RunHostFolders folders(Path root) {
        return new RunHostFolders(root, location -> Optional.of(new PluginCoordinate("com.github.LiQiyeDev",
                "my-plugin")), ValueGrammar::empty);
    }

    @Test
    void aRunReadsAndChangesItsOwnValueInPlace(@TempDir Path root) throws IOException {
        Path file = seeded(root);
        RunHostFolders folders = folders(root);

        assertEquals(Optional.of("first"), folders.read(RunHostFoldersTest.class, DONE));
        assertEquals(Optional.empty(), folders.update(RunHostFoldersTest.class, DONE, kept -> kept + " then second"));

        assertEquals(Optional.of("first then second"), folders.read(RunHostFoldersTest.class, DONE));
        assertTrue(Files.readString(file).contains("return \"first then second\";"));
        assertTrue(Files.exists(folder(root).resolve(RunHostFolders.LOCK_FILE)), "written under the folder's lock");
        try (Stream<Path> left = Files.list(file.getParent())) {
            assertEquals(1, left.count(), "no temporary file left");
        }
    }

    @Test
    void anotherJarsCallIsRefused(@TempDir Path root) throws IOException {
        Path file = seeded(root);
        RunHostFolders folders = folders(root);

        assertEquals(Optional.empty(), folders.read(Test.class, DONE));
        Optional<String> refused = folders.update(Test.class, DONE, kept -> "taken");

        assertTrue(refused.orElseThrow().contains("only its own"), refused.toString());
        assertEquals(RUNS, Files.readString(file), "nothing written");
    }

    @Test
    void aValueThisVersionCannotReadIsLeftAsItIs(@TempDir Path root) throws IOException {
        Path file = seeded(root);
        String newer = RUNS.replace("return \"first\";", "return String.valueOf(new Object());");
        Files.writeString(file, newer);

        Optional<String> refused = folders(root).update(RunHostFoldersTest.class, DONE, kept -> "mine");

        assertTrue(refused.orElseThrow().contains("left as it is"), refused.toString());
        assertEquals(newer, Files.readString(file), "a newer version's value is never replaced by a start");
    }

    /** The core that started the run had loaded a plugin from before the value: the refusal says what to do. */
    @Test
    void aValueTheClassLacksIsRefusedWithWhatToDo(@TempDir Path root) throws IOException {
        Path file = seeded(root);
        String before = RUNS.replaceAll("(?m)^ {4}.*\\n", "");
        Files.writeString(file, before);

        String refused = folders(root).update(RunHostFoldersTest.class, DONE, kept -> "x").orElseThrow();

        assertTrue(refused.contains("has no DONE"), refused);
        assertTrue(refused.contains("restart BotMaker's core"), refused);
        assertEquals(before, Files.readString(file));
    }

    @Test
    void aClassNotOnThisPcYetIsRefusedWithWhy(@TempDir Path root) {
        RunHostFolders folders = folders(root);

        assertEquals(Optional.empty(), folders.read(RunHostFoldersTest.class, DONE));
        assertTrue(folders.update(RunHostFoldersTest.class, DONE, kept -> "x").orElseThrow().contains("not on this PC"));
    }
}
