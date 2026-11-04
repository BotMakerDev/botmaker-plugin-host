package com.botmaker.plugin.host.grammar;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One parameter's value written into its group's source (2026-11-04). */
class ParamValuesTest {

    private static final String GROUP = """
            package com.refbot.params.bot;

            import com.botmaker.plugin.api.params.Param;

            @Param
            public class Progress {
                @Param.Shown(changes = Param.Changes.DURING_RUN)
                public int level() { return 1; }

                public int tries() { return 10; }

                public int computed() { int a = 2; return a; }
            }
            """;

    private static JavaValue value(String source, String... imports) {
        JavaValue parsed = JavaValue.parse(source).orElseThrow();
        return new JavaValue(parsed.node(), List.of(imports), source);
    }

    @Test
    void aValuesTimeIsReadOffItsMethod() {
        String timed = GROUP.replace("public int level()", "@Param.Kept(at = \"2026-11-04T10:00:00Z\") public int level()");

        assertEquals(Instant.parse("2026-11-04T10:00:00Z"), ParamValues.keptAt(timed, "Progress", "level").orElseThrow());
        assertTrue(ParamValues.keptAt(GROUP, "Progress", "level").isEmpty(), "no run wrote it");
        assertTrue(ParamValues.keptAt(timed.replace("2026-11-04T10:00:00Z", "soon"), "Progress", "level").isEmpty());
        assertTrue(ParamValues.keptAt(timed, "Progress", "gone").isEmpty());
    }

    @Test
    void aValueReplacesWhatTheMethodReturnsAndBringsItsImports() {
        String out = ParamValues.withReturned(GROUP, "Progress", "level",
                value("Duration.ZERO", "java.time.Duration", "java.lang.String")).orElseThrow();
        assertTrue(out.contains("return Duration.ZERO;"), out);
        assertTrue(out.contains("import java.time.Duration;"), out);
        assertFalse(out.contains("java.lang.String"), out);
        assertTrue(out.contains("return 10;"), out);
    }

    @Test
    void aBodyThatIsNotOneReturnOrAnUnknownMethodIsNotWritten() {
        assertEquals(Optional.empty(), ParamValues.withReturned(GROUP, "Progress", "computed", value("3")));
        assertEquals(Optional.empty(), ParamValues.withReturned(GROUP, "Progress", "absent", value("3")));
        assertEquals(Optional.empty(), ParamValues.withReturned("class {", "Progress", "level", value("3")));
    }

    @Test
    void aRunsValueIsTakenAndAPersonsEditStays() {
        String disk = GROUP.replace("return 1;", "return 7;").replace("return 10;", "return 99;");
        String held = GROUP.replace("return 10;", "return 3;");
        String out = ParamValues.withRunsValues(held, GROUP, disk);
        assertTrue(out.contains("return 7;"), out);
        assertTrue(out.contains("return 3;"), out);
    }

    /** Another library's {@code @Param.Shown} says nothing about when a value changes (doc 36). */
    @Test
    void aForeignShownIsNoDuringARunMark() {
        String foreign = GROUP.replace("import com.botmaker.plugin.api.params.Param;", "import other.lib.Param;");
        String disk = foreign.replace("return 1;", "return 7;");

        assertEquals(foreign, ParamValues.withRunsValues(foreign, foreign, disk), "the run's value is not taken");
        assertTrue(ParamValues.withRunsValues(GROUP, GROUP, GROUP.replace("return 1;", "return 7;"))
                .contains("return 7;"), "the contract's own mark is");
    }

    @Test
    void aDuringARunValueTheRunLeftAloneStaysAsThePersonEditedIt() {
        String held = GROUP.replace("return 1;", "return 4;");
        assertEquals(held, ParamValues.withRunsValues(held, GROUP, GROUP));
    }

    /** A value a run wrote carries when it did; a person's edit, written without a time, changes none. */
    @Test
    void aRunsValueCarriesItsTime() {
        Instant first = Instant.parse("2026-11-04T10:00:00Z");
        Instant second = Instant.parse("2026-11-04T11:00:00Z");

        String read = ParamValues.withReturned(GROUP, "Progress", "level", value("7"), first).orElseThrow();
        assertTrue(read.contains("@Param.Kept(at = \"2026-11-04T10:00:00Z\")"), read);
        assertTrue(read.contains("return 7;"), read);

        String again = ParamValues.withReturned(read, "Progress", "level", value("7"), second).orElseThrow();
        assertTrue(again.contains("\"2026-11-04T11:00:00Z\""), again);
        assertFalse(again.contains("10:00:00Z"), "one time, the latest: " + again);

        assertTrue(ParamValues.withReturned(read, "Progress", "level", value("8")).orElseThrow().contains("10:00:00Z"),
                "a write without a time leaves the one there");

        assertTrue(ParamValues.withRunsValues(GROUP, GROUP, read).contains("10:00:00Z"), "the run's merge carries it");
        assertTrue(ParamValues.withRunsValues(read, read, again).contains("11:00:00Z"),
                "the same value read again is a change: its new time");
    }

    @Test
    void anUnreadableDiskLeavesTheHeldCopy() {
        assertEquals(GROUP, ParamValues.withRunsValues(GROUP, GROUP, "class {"));
    }
}
