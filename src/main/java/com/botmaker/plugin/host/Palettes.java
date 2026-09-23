package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.plugin.api.palette.Palette;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * A plugin's palette, as a host reads it: the plugin's own {@link StudioPlugin#catalog()} when it builds one
 * by hand, otherwise every {@link Palette} class in the jar the plugin was loaded from.
 *
 * <p>The annotation is the declaration, so no plugin lists its classes. Discovery reads only the plugin's
 * own jar (or class directory), never the rest of the classpath: a class another jar annotates is that jar's
 * plugin's to offer.
 *
 * <p><b>A discovery links nothing.</b> Each class file is searched for the annotation's descriptor first, and
 * only a hit is loaded, without being initialised. A plugin's JavaFX half stays unlinked on a headless host.
 *
 * <p>Like {@link PaletteCatalog#of}, it degrades rather than throws: an unreadable jar or an unloadable
 * class is a line in {@link PaletteCatalog#problems()}.
 */
public final class Palettes {

    private static final byte[] MARKER =
            ("L" + Palette.class.getName().replace('.', '/') + ";").getBytes(StandardCharsets.US_ASCII);

    private Palettes() {
    }

    /**
     * The palette this plugin contributes: its hand-built {@code catalog()}, or the one discovered from its
     * jar when {@code catalog()} is the default. A {@code catalog()} that throws is left to the caller.
     */
    public static PaletteCatalog of(StudioPlugin plugin) {
        PaletteCatalog own = plugin.catalog();
        return isDefault(own) ? discover(plugin.getClass()) : own;
    }

    /**
     * Whether a plugin's {@code catalog()} left the palette to the host: nothing built and nothing reported.
     * A hand-built catalog whose every class was refused still has its problems, and keeps them.
     */
    public static boolean isDefault(PaletteCatalog own) {
        return own == null || own.isEmpty() && own.problems().isEmpty();
    }

    /** Every {@link Palette} class in the jar or class directory {@code anchor} was loaded from, catalogued. */
    public static PaletteCatalog discover(Class<?> anchor) {
        List<String> problems = new ArrayList<>();
        List<Class<?>> classes = annotated(anchor, problems);
        PaletteCatalog found = PaletteCatalog.of(classes.toArray(Class<?>[]::new));
        problems.addAll(found.problems());
        return new PaletteCatalog(found.facades(), problems);
    }

    private static List<Class<?>> annotated(Class<?> anchor, List<String> problems) {
        Path root = location(anchor, problems);
        if (root == null) return List.of();
        List<String> names = new ArrayList<>();
        try {
            if (Files.isDirectory(root)) {
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        String relative = root.relativize(file).toString().replace('\\', '/');
                        if (isClassFile(relative) && marked(Files.readAllBytes(file))) names.add(className(relative));
                    }
                }
            } else {
                try (JarFile jar = new JarFile(root.toFile())) {
                    for (JarEntry entry : Collections.list(jar.entries())) {
                        if (entry.isDirectory() || !isClassFile(entry.getName())) continue;
                        try (InputStream in = jar.getInputStream(entry)) {
                            if (marked(in.readAllBytes())) names.add(className(entry.getName()));
                        }
                    }
                }
            }
        } catch (IOException | UncheckedIOException e) {
            problems.add("cannot read " + root + " for @Palette classes (" + e + ")");
            return List.of();
        }
        Collections.sort(names);

        List<Class<?>> classes = new ArrayList<>();
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name, false, anchor.getClassLoader());
                if (type.isAnnotationPresent(Palette.class)) classes.add(type);
            } catch (ClassNotFoundException | LinkageError e) {
                problems.add(name + ": cannot be loaded (" + e + ")");
            }
        }
        return classes;
    }

    private static Path location(Class<?> anchor, List<String> problems) {
        try {
            CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            problems.add(anchor.getName() + ": cannot locate its jar (" + e + ")");
            return null;
        }
        problems.add(anchor.getName() + " has no jar or class directory to read");
        return null;
    }

    /** A class a loader can name: not {@code module-info}, {@code package-info} or a versioned copy. */
    private static boolean isClassFile(String path) {
        return path.endsWith(".class") && !path.startsWith("META-INF/")
                && !path.endsWith("module-info.class") && !path.endsWith("package-info.class");
    }

    private static String className(String path) {
        return path.substring(0, path.length() - ".class".length()).replace('/', '.');
    }

    private static boolean marked(byte[] bytes) {
        outer:
        for (int i = 0; i <= bytes.length - MARKER.length; i++) {
            for (int j = 0; j < MARKER.length; j++) {
                if (bytes[i + j] != MARKER[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
