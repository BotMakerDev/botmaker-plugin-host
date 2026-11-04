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

    /** A rename rule in the starting copy moves the old name's value to the new name, at the same type only. */
    @Test
    void aRenameRuleKeepsTheValueUnderItsNewName() {
        String rule = """
                    @Migrate(RunChoices.class)
                    static int tries(@Migrate.Self RunChoices self) { return self.attempts(); }

                    @Migrate(RunChoices.class)
                    static String account(@Migrate.Self RunChoices self) { return self.login(); }
                """;
        String starting = STARTING.replace("import java.time.Duration;",
                        "import java.time.Duration;\nimport com.botmaker.plugin.api.meta.Migrate;")
                .replace("public int tries()", "public int attempts()")
                .replace("public String account() { return \"\"; }", "public long login() { return 0; }\n\n" + rule);

        String fitted = flat(ParamsFitter.fit(starting, MINE));

        assertTrue(fitted.contains("public int attempts() { return 3; }"), "moved by the rule: " + fitted);
        assertTrue(fitted.contains("public long login() { return 0; }"), "a retype moves nothing: " + fitted);
        assertFalse(fitted.contains("public int tries()") || fitted.contains("\"main\""), fitted);
        assertEquals(List.of("renamed tries() to attempts()", "added login()", "dropped account()"),
                ParamsFitter.over(Map.of(FILE, MINE), Map.of(FILE, starting)).getFirst().changes());
    }

    /** A type is what its imports resolve to: {@code a.Foo} is not {@code b.Foo}, an import is its whole name. */
    @Test
    void typesAreComparedByWhatTheyResolveTo() {
        String starting = STARTING.replace("import java.time.Duration;", "import java.time.Duration;\nimport a.Foo;")
                .replace("public String account() { return \"\"; }", "public Foo kind() { return Foo.X; }");
        String mine = MINE.replace("import java.time.Duration;", "import java.time.Duration;\nimport b.Foo;")
                .replace("public String account() { return \"main\"; }", "public Foo kind() { return Foo.Y; }");

        String fitted = flat(ParamsFitter.fit(starting, mine));
        assertTrue(fitted.contains("public Foo kind() { return Foo.X; }"), "another Foo is reset: " + fitted);
        String onDemand = flat(ParamsFitter.fit(starting, mine.replace("import b.Foo;", "import b.*;")));
        assertTrue(onDemand.contains("return Foo.X;"), "nor is b.*'s Foo a.Foo: " + onDemand);
        String same = flat(ParamsFitter.fit(starting, mine.replace("import b.Foo;", "import a.*;")));
        assertTrue(same.contains("return Foo.Y;"), "a.*'s Foo is: " + same);

        String whole = MINE.replace("public Duration rest()", "public java.time.Duration rest()");
        assertTrue(flat(ParamsFitter.fit(STARTING, whole)).contains("return Duration.ofSeconds(5);"),
                "an imported Duration and one written whole are one type: " + ParamsFitter.fit(STARTING, whole));
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

    /** When a run wrote a value belongs to the value: it follows the value, and never reaches a starting copy. */
    @Test
    void aValueKeepsItsTimeThroughAFit() {
        String kept = "@Param.Kept(at = \"2026-11-04T10:00:00Z\")";
        String mine = MINE.replace("public int tries()", kept + " public int tries()");

        assertEquals(mine, ParamsFitter.fit(STARTING, mine), "a time is no reason to fit");

        String reshaped = STARTING.replace("Attempts before giving up", "Attempts");
        String fitted = flat(ParamsFitter.fit(reshaped, mine));
        assertTrue(fitted.contains("\"Attempts\")"), fitted);
        assertTrue(fitted.contains(kept) && fitted.contains("public int tries() { return 3; }"), fitted);

        String retyped = flat(ParamsFitter.fit(STARTING.replace("int tries", "long tries"), mine));
        assertFalse(retyped.contains("Kept"), "a reset value has no time: " + retyped);

        assertFalse(ParamsFitter.withStartingValue(STARTING, mine, "tries").contains("Kept"), "never in a starting copy");
        String again = mine.replace("10:00:00Z", "11:00:00Z");
        String timed = ParamsFitter.withValue(mine, again, "tries");
        assertTrue(timed.contains("11:00:00Z") && !timed.contains("10:00:00Z"), "the same value, its new time: " + timed);
        String forgotten = flat(ParamsFitter.withValue(mine, STARTING, "tries"));
        assertFalse(forgotten.contains("Kept"), "set back to its starting value: " + forgotten);
        assertTrue(forgotten.contains("public int tries() { return 10; }"), forgotten);
    }

    @Test
    void aMissingParameterIsAddedWithItsStartingValue(@TempDir Path bot) throws IOException {
        Path params = bot.resolve("src/main/java/com/refbot/params");
        Path starting = bot.resolve(ParamsFitter.STARTING);
        Path backups = bot.resolve(".botmaker/values-removed");
        String mine = MINE.replace("\n    public String account() { return \"main\"; }\n", "");
        write(params.resolve(FILE), mine);
        write(params.resolve("sdk/Gone.java"), "package com.refbot.params.sdk;\nimport com.botmaker.plugin.api.params.Param;\n@Param\npublic class Gone {}\n");
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
