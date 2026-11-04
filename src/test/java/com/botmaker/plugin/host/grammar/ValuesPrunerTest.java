package com.botmaker.plugin.host.grammar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A values class that no longer fits its schema never stops a build (2026-11-04). */
class ValuesPrunerTest {

    private static final String SCHEMA = """
            package com.refbot;

            import com.botmaker.plugin.api.params.Param;
            import java.time.Duration;

            @Param
            public interface RunChoices {
                default int tries() { return 10; }
                default Duration rest() { return Duration.ofMinutes(1); }
            }
            """;

    private static final String VALUES = """
            package com.refbot.values;

            import com.refbot.RunChoices;
            import com.refbot.Mode;
            import java.time.Duration;

            public class RunChoicesValues implements RunChoices {
                @Override
                public int tries() { return 3; }

                @Override
                public Duration rest() { return Duration.ofSeconds(5); }

                @Override
                public Mode mode() { return Mode.FAST; }

                public String name() { return "gone"; }
            }
            """;

    @Test
    void aValuesClassIsPrunedToItsSchema() {
        String retyped = SCHEMA.replace("default Duration rest() { return Duration.ofMinutes(1); }",
                "default long rest() { return 60; }");

        String pruned = ValuesPruner.prune(retyped, VALUES).orElseThrow();

        assertTrue(pruned.contains("public int tries() { return 3; }"), pruned);
        assertFalse(pruned.contains("rest()"), "retyped: " + pruned);
        assertFalse(pruned.contains("mode()"), "not in the schema: " + pruned);
        assertTrue(pruned.contains("name()"), "a helper overrides nothing, so it stays: " + pruned);
        assertFalse(pruned.contains("import com.refbot.Mode;"), "nothing uses it now: " + pruned);
        assertFalse(pruned.contains("import java.time.Duration;"), "nothing uses it now: " + pruned);
        assertTrue(pruned.contains("import com.refbot.RunChoices;"), pruned);
    }

    @Test
    void aFittingValuesClassIsLeftAlone() {
        String fits = VALUES.replace("""
                    @Override
                    public Mode mode() { return Mode.FAST; }

                    public String name() { return "gone"; }
                """, "").replace("import com.refbot.Mode;\n", "");

        assertEquals(fits, ValuesPruner.prune(SCHEMA, fits).orElseThrow());
    }

    @Test
    void aClassWhoseSchemaIsGoneIsDeleted() {
        assertEquals(Optional.empty(), ValuesPruner.prune(null, VALUES));
        String notASchema = SCHEMA.replace("@Param\n", "");
        assertEquals(Optional.empty(), ValuesPruner.prune(notASchema, VALUES));
    }

    @Test
    void theTreeIsPrunedOnDisk(@TempDir Path root) throws IOException {
        Path schema = write(root.resolve("com/refbot/RunChoices.java"), SCHEMA.replace(
                "    default Duration rest() { return Duration.ofMinutes(1); }\n", ""));
        Path values = write(root.resolve("com/refbot/values/RunChoicesValues.java"), VALUES);
        Path orphan = write(root.resolve("com/refbot/values/OldValues.java"),
                "package com.refbot.values;\n\npublic class OldValues implements Old {\n}\n");

        Path backups = root.resolve("backups");
        List<ValuesPruner.Pruned> pruned = ValuesPruner.onDisk(root.resolve("com"), backups);

        assertEquals(2, pruned.size(), pruned.toString());
        assertFalse(Files.exists(orphan));
        String kept = Files.readString(values);
        assertTrue(kept.contains("tries()") && !kept.contains("rest()"), kept);
        assertTrue(Files.exists(schema));
        assertEquals(VALUES, Files.readString(backups.resolve("RunChoicesValues.java")), "the value is kept aside");
        assertTrue(Files.exists(backups.resolve("OldValues.java")));
        assertTrue(ValuesPruner.onDisk(root.resolve("com"), backups).isEmpty(), "a second pass has nothing to do");
    }

    @Test
    void nothingIsDecidedWhileASourceDoesNotParse() {
        String midEdit = SCHEMA.replace("default int tries() { return 10; }", "default long tries() { return 10;");
        assertTrue(ValuesPruner.over(Map.of(
                Path.of("com/refbot/RunChoices.java"), midEdit,
                Path.of("com/refbot/values/RunChoicesValues.java"), VALUES)).isEmpty());
    }

    @Test
    void aSchemaFromAJarIsNoSchemaGone() {
        String jars = VALUES.replace("import com.refbot.RunChoices;", "import com.plugin.RunChoices;");
        assertTrue(ValuesPruner.over(Map.of(
                Path.of("com/refbot/Bot.java"), "package com.refbot;\n\nclass Bot {}\n",
                Path.of("com/refbot/values/RunChoicesValues.java"), jars)).isEmpty());
    }

    @Test
    void onlyAValuesPackageHoldsValuesClasses() {
        assertTrue(ValuesPruner.isValuesFile(Path.of("src/main/java/com/refbot/values/RunChoicesValues.java")));
        assertFalse(ValuesPruner.isValuesFile(Path.of("src/main/java/com/refbot/RunChoices.java")));
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }
}
