package com.botmaker.plugin.host.grammar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A private parameter file that no longer fits its starting copy never stops a build (2026-11-04). */
class ParamsFitterTest {

    private static final String STARTING = """
            package com.refbot.params.bot;

            import com.botmaker.plugin.api.params.Param;
            import java.time.Duration;

            @Param
            public class RunChoices {
                @Param.Shown(description = "Attempts before giving up")
                public int tries() { return 10; }

                public Duration rest() { return Duration.ofMinutes(1); }

                public String account() { return ""; }
            }
            """;

    private static final String MINE = """
            package com.refbot.params.bot;

            import com.botmaker.plugin.api.params.Param;
            import com.refbot.Mode;
            import java.time.Duration;

            @Param
            public class RunChoices {
                @Param.Shown(description = "Attempts before giving up")
                public int tries() { return 3; }

                public Duration rest() { return Duration.ofSeconds(5); }

                public String account() { return "main"; }
            }
            """;

    private static final Path FILE = Path.of("bot", "RunChoices.java");

    @Test
    void paramsAreFittedToTheirStartingCopy() {
        String starting = STARTING
                .replace("public Duration rest() { return Duration.ofMinutes(1); }", "public long rest() { return 60; }")
                .replace("public String account() { return \"\"; }",
                        "@Param.Shown(description = \"Your account\")\n    public String account() { return \"\"; }");
        String mine = MINE.replace("public String account() { return \"main\"; }",
                "public String account() { return \"main\"; }\n\n    public Mode mode() { return Mode.FAST; }");

        String fitted = flat(ParamsFitter.fit(starting, mine));

        assertTrue(fitted.contains("public int tries() { return 3; }"), "kept: " + fitted);
        assertTrue(fitted.contains("public long rest() { return 60; }"), "retyped, reset: " + fitted);
        assertTrue(fitted.contains("\"Your account\") public String account() { return \"main\"; }"),
                "the starting copy's annotations, this computer's value: " + fitted);
        assertFalse(fitted.contains("mode()"), "not in the starting copy: " + fitted);
        assertFalse(fitted.contains("import com.refbot.Mode;"), "no kept value uses it: " + fitted);
        assertEquals(MINE, ParamsFitter.fit(STARTING, MINE), "a fitting file is left alone");

        String made = ParamsFitter.withValue(STARTING, MINE.replace("Duration.ofSeconds(5)", "Mode.REST"), "account");
        assertTrue(made.contains("return \"main\";") && made.contains("return 10;"), "one value, no other: " + made);
        assertFalse(made.contains("import com.refbot.Mode;"), "only what that value uses: " + made);
        String rest = ParamsFitter.withValue(STARTING.replace("Duration rest", "Mode rest")
                .replace("Duration.ofMinutes(1)", "Mode.FAST"), MINE.replace("Duration rest", "Mode rest")
                .replace("Duration.ofSeconds(5)", "Mode.REST"), "rest");
        assertTrue(rest.contains("return Mode.REST;") && rest.contains("import com.refbot.Mode;"), rest);

        String overMode =ParamsFitter.fit(starting, mine.replace("return \"main\";", "return mode().name();"));
        assertTrue(overMode.contains("public String account() { return \"\"; }"),
                "a value over a dropped parameter is reset, never left not compiling: " + overMode);
    }

    /** The trees decide, so spacing never makes a difference and a value already there keeps its own text. */
    @Test
    void aBodyWrittenAlikeKeepsItsText() {
        String spaced = STARTING.replace("return 3;", "return    3;");

        assertEquals(STARTING, ParamsFitter.withValue(STARTING, spaced, "tries"), "the same value changes nothing");
        assertEquals(spaced, ParamsFitter.fit(STARTING, spaced), "only spacing differs: this file already fits");
    }

    @Test
    void aValueBringsItsImportAndKeepsTheOnesThere() {
        String starting = STARTING.replace("Duration rest", "Mode rest").replace("Duration.ofMinutes(1)", "Mode.FAST");
        String mine = MINE.replace("Duration rest", "Mode rest").replace("Duration.ofSeconds(5)", "Mode.REST");

        String made = ParamsFitter.withValue(starting, mine, "rest");

        assertEquals(made.indexOf("import com.refbot.Mode;"), made.lastIndexOf("import com.refbot.Mode;"), made);
        assertTrue(made.contains("import com.refbot.Mode;"), made);
        assertTrue(made.contains("return Mode.REST;"), made);
        assertEquals(made, ParamsFitter.withValue(made, mine, "rest"), "written once, it is not written again");
    }

    @Test
    void aMissingParameterIsAddedWithItsStartingValue(@TempDir Path bot) throws IOException {
        Path params = bot.resolve("src/main/java/com/refbot/params");
        Path starting = bot.resolve(ParamsFitter.STARTING);
        Path backups = bot.resolve(".botmaker/values-removed");
        String mine = MINE.replace("\n    public String account() { return \"main\"; }\n", "");
        write(params.resolve(FILE), mine);
        write(params.resolve("sdk/Gone.java"), "package com.refbot.params.sdk;\n@Param\npublic class Gone {}\n");
        String fields = "package com.refbot.params;\npublic final class Parameters {\n    @Param public static int a = 1;\n}\n";
        write(params.resolve("Parameters.java"), fields);
        write(starting.resolve(FILE), STARTING);
        write(starting.resolve("sdk/Sdk.java"), "package com.refbot.params.sdk;\npublic class Sdk {}\n");

        List<ParamsFitter.Fitted> fitted = ParamsFitter.onDisk(params, starting, backups);

        assertEquals(3, fitted.size(), fitted.toString());
        String now = flat(Files.readString(params.resolve(FILE)));
        assertTrue(now.contains("public String account() { return \"\"; }"), "added with its starting value: " + now);
        assertTrue(now.contains("public int tries() { return 3; }"), now);
        assertTrue(Files.exists(params.resolve("sdk/Sdk.java")), "a file the private copy lacks is copied");
        assertFalse(Files.exists(params.resolve("sdk/Gone.java")), "its starting copy is gone");
        assertEquals(fields, Files.readString(params.resolve("Parameters.java")), "no @Param class: not a group");
        Path pass;
        try (var each = Files.list(backups)) {
            pass = each.findFirst().orElseThrow();
        }
        assertEquals(mine, Files.readString(pass.resolve(FILE)), "backed up at its own path, in this pass's folder");
        assertTrue(Files.exists(pass.resolve("sdk/Gone.java")));
    }

    @Test
    void nothingIsDecidedWhileASourceDoesNotParse(@TempDir Path bot) throws IOException {
        Map<Path, String> mine = Map.of(FILE, MINE.replace("return 3;", "return 3"));

        assertEquals(List.of(), ParamsFitter.over(mine, Map.of(FILE, STARTING.replace("int tries", "long tries"))));
        assertEquals(List.of(), ParamsFitter.over(Map.of(FILE, MINE), Map.of(FILE, STARTING + "}")));

        Path params = bot.resolve("params");
        write(params.resolve(FILE), MINE);
        assertEquals(List.of(), ParamsFitter.onDisk(params, bot.resolve(ParamsFitter.STARTING), bot.resolve("b")),
                "no starting copy at all: a bot loses no parameter");
        assertEquals(MINE, Files.readString(params.resolve(FILE)));
    }

    /** The rewrite lays out a body it copies: these tests read what is written, not how it is spaced. */
    private static String flat(String source) {
        return source.replaceAll("\\s+", " ");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
