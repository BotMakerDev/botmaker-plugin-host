package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.record.Gesture;
import com.botmaker.plugin.api.record.RecordedValue;
import com.botmaker.plugin.api.record.Records;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The {@link Records} methods of a set of plugins, each with how the host fills every parameter.
 *
 * <p>Found on the classes {@link Palettes} discovers — nothing is listed. Each parameter is classified once, by
 * the rule {@link Records} documents; a method with a parameter no rule covers is a {@link #problems problem},
 * never a writer. Types are compared by name, since two plugins' loaders may each hold a copy of one class.
 */
public final class Recordings {

    private Recordings() {
    }

    /** One way to write a gesture down: the method, and how each of its parameters is filled. */
    public record Writer(StudioPlugin plugin, Method method, Gesture gesture, int rank, List<Slot> slots) {

        public Writer {
            slots = List.copyOf(slots);
        }
    }

    /** How the host fills one parameter. */
    public sealed interface Slot {

        /** A plugin's {@link RecordedValue}, resolved at the gesture's spot. */
        record Recorded(RecordedValue<?> answer) implements Slot {}

        /** {@code int}, {@code long} or {@code double}: the gesture's next value. */
        record Number(Class<?> type) implements Slot {}

        /** A type whose components are all numbers: that many of the gesture's next values, built. */
        record Parts(ComponentType<?> type, List<Class<?>> components) implements Slot {}

        /** {@code String}: the typed text. */
        record Text() implements Slot {}

        /** An enum, or an enum array: the key names, looked up with {@code valueOf}. */
        record Keys(Class<?> enumType, boolean many) implements Slot {}

        /** A type a plugin gives a fresh value to: that value's Java. */
        record Fresh(String typeName) implements Slot {}
    }

    /** Every writer the plugins declare, highest {@link Writer#rank()} first within each gesture. */
    public static List<Writer> of(List<StudioPlugin> plugins) {
        List<Writer> out = new ArrayList<>();
        for (StudioPlugin plugin : plugins) {
            for (Method method : annotated(plugin)) {
                Records records = method.getAnnotation(Records.class);
                classify(method, plugins).ifPresent(slots ->
                        out.add(new Writer(plugin, method, records.value(), records.rank(), slots)));
            }
        }
        out.sort(Comparator.comparingInt((Writer w) -> w.gesture().ordinal())
                .thenComparing(Comparator.comparingInt(Writer::rank).reversed()));
        return List.copyOf(out);
    }

    /**
     * What is wrong with {@code plugin}'s {@link Records} methods, one sentence each: a method that is not
     * {@code public static}, or a parameter nothing can fill. {@code all} is every plugin a host would load
     * beside it, since another plugin may answer a parameter type.
     */
    public static List<String> problems(StudioPlugin plugin, List<StudioPlugin> all) {
        List<String> out = new ArrayList<>();
        for (Method method : annotated(plugin)) {
            String name = method.getDeclaringClass().getName() + "#" + method.getName();
            if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())) {
                out.add(name + " carries @Records but is not public static");
                continue;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                if (slot(parameter, all).isEmpty()) {
                    out.add(name + " carries @Records but nothing fills its " + parameter.getName()
                            + " parameter");
                }
            }
        }
        return out;
    }

    private static List<Method> annotated(StudioPlugin plugin) {
        List<Method> out = new ArrayList<>();
        for (FacadeEntry facade : Palettes.of(plugin).facades()) {
            Method[] methods;
            try {
                methods = facade.type().getMethods();
            } catch (LinkageError e) {
                continue;
            }
            for (Method method : methods) {
                if (method.getDeclaringClass() == facade.type() && method.isAnnotationPresent(Records.class)) {
                    out.add(method);
                }
            }
        }
        return out;
    }

    private static Optional<List<Slot>> classify(Method method, List<StudioPlugin> plugins) {
        if (!Modifier.isStatic(method.getModifiers())) return Optional.empty();
        List<Slot> slots = new ArrayList<>();
        for (Class<?> parameter : method.getParameterTypes()) {
            Optional<Slot> slot = slot(parameter, plugins);
            if (slot.isEmpty()) return Optional.empty();
            slots.add(slot.get());
        }
        return Optional.of(slots);
    }

    /** The first rule of {@link Records} that fills {@code type}, or empty. */
    static Optional<Slot> slot(Class<?> type, List<StudioPlugin> plugins) {
        String name = type.getName();
        for (StudioPlugin plugin : plugins) {
            for (RecordedValue<?> answer : safe(plugin::recordedValues)) {
                if (answer.type() != null && answer.type().getName().equals(name)) {
                    return Optional.of(new Slot.Recorded(answer));
                }
            }
        }
        if (type == int.class || type == long.class || type == double.class) return Optional.of(new Slot.Number(type));
        Optional<ComponentType<?>> parts = numericComponents(name, plugins);
        if (parts.isPresent()) return Optional.of(new Slot.Parts(parts.get(), parts.get().componentTypes()));
        if (type == String.class) return Optional.of(new Slot.Text());
        if (type.isEnum()) return Optional.of(new Slot.Keys(type, false));
        if (type.isArray() && type.getComponentType().isEnum()) {
            return Optional.of(new Slot.Keys(type.getComponentType(), true));
        }
        if (hasFresh(name, plugins)) return Optional.of(new Slot.Fresh(name));
        return Optional.empty();
    }

    private static Optional<ComponentType<?>> numericComponents(String name, List<StudioPlugin> plugins) {
        for (StudioPlugin plugin : plugins) {
            List<ComponentType<?>> candidates = new ArrayList<>(safe(plugin::componentTypes));
            for (PluginType<?> type : safe(plugin::types)) {
                if (type instanceof ComponentType<?> component) candidates.add(component);
            }
            for (ComponentType<?> component : candidates) {
                if (component.type() == null || !component.type().getName().equals(name)) continue;
                List<Class<?>> parts = component.componentTypes();
                if (!parts.isEmpty() && parts.stream().allMatch(p -> p == int.class || p == long.class
                        || p == double.class)) {
                    return Optional.of(component);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean hasFresh(String name, List<StudioPlugin> plugins) {
        for (StudioPlugin plugin : plugins) {
            for (PluginType<?> type : safe(plugin::types)) {
                if (type.type() == null || !type.type().getName().equals(name)) continue;
                try {
                    if (type.freshSource() != null || type.fresh() != null) return true;
                } catch (RuntimeException | LinkageError e) {
                    return false;
                }
            }
        }
        return false;
    }

    /** A plugin surface that throws or links badly contributes nothing, as everywhere else on the host. */
    private static <T> List<T> safe(java.util.function.Supplier<List<T>> surface) {
        try {
            List<T> list = surface.get();
            return list == null ? List.of() : list;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }
}
