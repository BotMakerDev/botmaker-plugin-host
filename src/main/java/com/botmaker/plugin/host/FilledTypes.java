package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.palette.Fills;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The values a plugin's bot fills in, as a host reads them: every {@link Fills} method in the jar the plugin was
 * loaded from, each declaring its return type. The host turns each into a type whose fresh value is a call to the
 * method, drawn as a pill saying its label; this module has no JavaFX, so it hands over what it found and the
 * host draws.
 *
 * <p>Like {@link Palettes}, discovery reads only the plugin's own jar, links nothing ({@link JarClasses}) and
 * degrades rather than throws: a method of the wrong shape, a class filled twice or an unreadable jar is a line in
 * {@link Found#problems()} and declares nothing. Found once per plugin class and kept, since every host asks
 * for a plugin's types many times and the jar does not change under a loaded class.
 */
public final class FilledTypes {

    /**
     * One {@link Fills} method: the class it fills, the call a fresh value is written as, and what the pill says
     * — the annotation's label, or the class's simple name.
     */
    public record Filled(Class<?> type, Method call, String label) {
    }

    /** What a plugin's jar fills, by class name, and what was refused. */
    public record Found(List<Filled> filled, List<String> problems) {
        public Found {
            filled = List.copyOf(filled);
            problems = List.copyOf(problems);
        }
    }

    private static final ClassValue<Found> FOUND = new ClassValue<>() {
        @Override
        protected Found computeValue(Class<?> anchor) {
            return discover(anchor);
        }
    };

    private FilledTypes() {
    }

    /** What {@code plugin}'s jar fills; found on the first ask for its class. */
    public static Found of(StudioPlugin plugin) {
        return FOUND.get(plugin.getClass());
    }

    /** Every {@link Fills} method in the jar or class directory {@code anchor} was loaded from. */
    public static Found discover(Class<?> anchor) {
        List<String> problems = new ArrayList<>();
        Map<String, Filled> byType = new HashMap<>();
        List<Filled> filled = new ArrayList<>();
        for (Class<?> owner : JarClasses.naming(anchor, Fills.class, problems)) {
            Method[] methods;
            try {
                methods = owner.getDeclaredMethods();
            } catch (LinkageError e) {
                problems.add(owner.getName() + ": its methods cannot be read (" + e + ")");
                continue;
            }
            List<Method> marked = new ArrayList<>();
            for (Method method : methods) if (method.isAnnotationPresent(Fills.class)) marked.add(method);
            marked.sort(Comparator.comparing(Method::getName));
            for (Method method : marked) {
                String name = owner.getName() + "." + method.getName() + "()";
                String why = shapeProblem(method);
                if (why != null) {
                    problems.add("@Fills " + name + " " + why);
                    continue;
                }
                Class<?> type = method.getReturnType();
                Filled before = byType.get(type.getName());
                if (before != null) {
                    problems.add("@Fills " + name + ": " + type.getName() + " is already filled by "
                            + before.call().getDeclaringClass().getName() + "." + before.call().getName()
                            + "(); a class is filled by one method");
                    continue;
                }
                String label = method.getAnnotation(Fills.class).label();
                Filled found = new Filled(type, method, label.isBlank() ? type.getSimpleName() : label.strip());
                byType.put(type.getName(), found);
                filled.add(found);
            }
        }
        return new Found(filled, problems);
    }

    /** Why {@code method} cannot fill its return type, or {@code null} when it can. */
    static String shapeProblem(Method method) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)) return "is not public static";
        if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) return "is on a class a bot cannot name";
        if (method.getParameterCount() != 0) return "takes parameters; a fresh value is a call with none";
        Class<?> type = method.getReturnType();
        if (type.isPrimitive() || type.isArray()) return "returns " + type.getName() + ", not a class it fills";
        return null;
    }
}
