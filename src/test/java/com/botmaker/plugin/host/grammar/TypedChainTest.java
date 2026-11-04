package com.botmaker.plugin.host.grammar;

import com.botmaker.plugin.api.value.ComponentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chain whose link answers another class than its receiver: {@code Gauge.of("gold").atLeast(5)}, where
 * {@code Gauge.of} starts a {@code Gauge} and {@code atLeast} finishes a {@code Limit}. Each class is declared by its
 * own call, the finishing one an instance method whose receiver is part 0, and the host writes the receiver by its
 * own declaration (2026-11-04).
 */
class TypedChainTest {

    public record Gauge(String name) {
        public static Gauge of(String name) { return new Gauge(name); }
        public Limit atLeast(int bound) { return new Limit(this, bound, true); }
        public Limit atMost(int bound) { return new Limit(this, bound, false); }
        public Limit any() { return new Limit(this, 0, true); }
    }

    public record Limit(Gauge gauge, int bound, boolean low) {}

    private static final ComponentType<Gauge> GAUGE = ComponentType.part(Gauge.class)
            .<String, Gauge>writtenAs(Gauge::of, Gauge::name);

    private static final ComponentType<Limit> AT_LEAST = ComponentType.part(Limit.class)
            .<Gauge, Integer, Limit>writtenAs(Gauge::atLeast, Limit::gauge, Limit::bound);

    private static final ComponentType<Limit> AT_MOST = ComponentType.part(Limit.class)
            .<Gauge, Integer, Limit>writtenAs(Gauge::atMost, Limit::gauge, Limit::bound);

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(), List.of(GAUGE, AT_LEAST, AT_MOST));

    private static final String GAUGE_NAME = JavaNames.canonical(Gauge.class);

    @Test
    void aLinkOfAnotherClassIsWrittenOnItsReceiversOwnCall() {
        assertEquals(Optional.of(GAUGE_NAME + ".of(\"gold\").atLeast(5)"),
                GRAMMAR.initializer(Limit.class, new Limit(new Gauge("gold"), 5, true)).map(JavaValue::source));
        assertEquals(Optional.of(GAUGE_NAME + ".of(\"gold\").atMost(5)"),
                GRAMMAR.initializer(Limit.class, new Limit(new Gauge("gold"), 5, false)).map(JavaValue::source),
                "the declaration that rebuilds it equal writes it");
    }

    @Test
    void itReadsBackAsTheValueItBuilds() {
        Limit limit = new Limit(new Gauge("gold"), 5, false);
        String written = GRAMMAR.initializer(Limit.class, limit).map(JavaValue::source).orElseThrow();

        assertEquals(Optional.of(limit), GRAMMAR.valueOf(Limit.class, written));
        assertEquals(Optional.of(limit), GRAMMAR.valueOf(Limit.class, "Gauge.of(\"gold\").atMost(5)"));
        assertEquals(Optional.of(limit), GRAMMAR.valueOfAny("Gauge.of(\"gold\").atMost(5)"));
    }

    @Test
    void aReceiverNothingWritesWritesNothing() {
        ValueGrammar noGauge = ValueGrammar.of(List.of(), List.of(AT_LEAST));

        assertTrue(noGauge.initializer(Limit.class, new Limit(new Gauge("gold"), 5, true)).isEmpty());
        assertTrue(GRAMMAR.valueOf(Limit.class, "somebody.atLeast(5)").isEmpty(), "a receiver nothing reads");
        assertTrue(GRAMMAR.valueOf(Limit.class, "Gauge.of(\"gold\").any()").isEmpty(), "a link nothing declares");
    }
}
