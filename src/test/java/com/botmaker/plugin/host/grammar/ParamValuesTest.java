package com.botmaker.plugin.host.grammar;

import org.junit.jupiter.api.Test;

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

    @Test
    void aDuringARunValueTheRunLeftAloneStaysAsThePersonEditedIt() {
        String held = GROUP.replace("return 1;", "return 4;");
        assertEquals(held, ParamValues.withRunsValues(held, GROUP, GROUP));
    }

    @Test
    void anUnreadableDiskLeavesTheHeldCopy() {
        assertEquals(GROUP, ParamValues.withRunsValues(GROUP, GROUP, "class {"));
    }
}
