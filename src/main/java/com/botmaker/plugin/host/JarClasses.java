package com.botmaker.plugin.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
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
 * The classes of a plugin's own jar (or class directory) that use an annotation, for the host's discoveries:
 * {@link Palettes} and {@link FilledTypes}.
 *
 * <p><b>A discovery links nothing.</b> Each class file is searched for the annotation's descriptor first, and
 * only a hit is loaded, without being initialised. A plugin's JavaFX half stays unlinked on a headless host. A
 * hit is a class that names the annotation anywhere — on itself or on a member — so the caller checks where.
 */
final class JarClasses {

    private JarClasses() {
    }

    /**
     * Every class in the jar or class directory {@code anchor} was loaded from whose class file names
     * {@code annotation}, loaded through {@code anchor}'s loader, by name. What cannot be read or loaded is a line
     * in {@code problems}.
     */
    static List<Class<?>> naming(Class<?> anchor, Class<? extends Annotation> annotation, List<String> problems) {
        byte[] marker = ("L" + annotation.getName().replace('.', '/') + ";").getBytes(StandardCharsets.US_ASCII);
        Path root = location(anchor, problems);
        if (root == null) return List.of();
        List<String> names = new ArrayList<>();
        try {
            if (Files.isDirectory(root)) {
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        String relative = root.relativize(file).toString().replace('\\', '/');
                        if (isClassFile(relative) && marked(Files.readAllBytes(file), marker)) {
                            names.add(className(relative));
                        }
                    }
                }
            } else {
                try (JarFile jar = new JarFile(root.toFile())) {
                    for (JarEntry entry : Collections.list(jar.entries())) {
                        if (entry.isDirectory() || !isClassFile(entry.getName())) continue;
                        try (InputStream in = jar.getInputStream(entry)) {
                            if (marked(in.readAllBytes(), marker)) names.add(className(entry.getName()));
                        }
                    }
                }
            }
        } catch (IOException | UncheckedIOException e) {
            problems.add("cannot read " + root + " for @" + annotation.getSimpleName() + " (" + e + ")");
            return List.of();
        }
        Collections.sort(names);

        List<Class<?>> classes = new ArrayList<>();
        for (String name : names) {
            try {
                classes.add(Class.forName(name, false, anchor.getClassLoader()));
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

    private static boolean marked(byte[] bytes, byte[] marker) {
        outer:
        for (int i = 0; i <= bytes.length - marker.length; i++) {
            for (int j = 0; j < marker.length; j++) {
                if (bytes[i + j] != marker[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
