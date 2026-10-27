package com.botmaker.plugin.host;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which plugin a classpath entry is, read off its poms and nothing else (2026-10-27).
 *
 * <p>A plugin says which class it is the way an npm package states its entry points in its one
 * {@code package.json}: in the one file every Maven artifact has exactly one of. Its pom sets
 * {@code <properties><botmaker.plugin>com.example.MyPlugin</botmaker.plugin></properties>}, and Maven's jar
 * packaging copies that pom to {@code META-INF/maven/<groupId>/<artifactId>/pom.xml}. So the identity (the
 * coordinate), the name a user reads ({@code <name>}) and the class to construct are all known before any of
 * the plugin's code runs, and none of them is a second statement to drift from the build.
 *
 * <p>An entry whose poms name no class is a library. One that has <em>two</em> poms naming a class — a plugin
 * shaded into another — is reported as such, and the loader refuses it: one jar is one plugin, because every
 * jar-scoped answer (its palette, its {@code @Fills}, which plugin owns a class) needs it. A pom that names a
 * class but cannot be used — no coordinate, an unresolved {@code ${…}}, XML that does not parse — is a problem
 * the loader reports, not a library: a plugin must never vanish without a word.
 *
 * <p>The pom is read as written, not as Maven's effective model: the property must be literal in the jar's pom,
 * which is why a flattened plugin pom keeps {@code properties}. A class directory has no {@code META-INF/maven}:
 * Maven writes it only into the jar. A {@code target/classes} entry reads the module's {@code pom.xml} two levels
 * up instead, which is what {@code botmaker plugin validate} run in a plugin's folder loads.
 *
 * <p>Read with {@code maven-model}, the parser Studio and the CLI read poms with. No plugin code runs here.
 */
public final class PluginPoms {

    /** The property a plugin's pom names its class by. */
    public static final String PROPERTY = "botmaker.plugin";

    private static final String MAVEN = "META-INF/maven/";

    private PluginPoms() {
    }

    /**
     * One pom that names a plugin class.
     *
     * @param coordinate  the pom's {@code groupId} (or its parent's) and {@code artifactId}
     * @param name        its {@code <name>}, or the artifactId when it has none
     * @param pluginClass the binary name {@value #PROPERTY} gives
     */
    public record PluginPom(PluginCoordinate coordinate, String name, String pluginClass) {
    }

    /**
     * What an entry's poms say: each pom naming a class, and a sentence per pom that tried to and cannot be used.
     * No pom and no problem is a library.
     */
    public record Found(List<PluginPom> poms, List<String> problems) {

        public Found {
            poms = List.copyOf(poms);
            problems = List.copyOf(problems);
        }
    }

    /** What {@code entry}, a jar or a class directory, declares. */
    public static Found find(Path entry) {
        List<PluginPom> poms = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Pom pom : poms(entry)) parse(pom, poms, problems);
        return new Found(poms, problems);
    }

    /** Every pom in {@code entry} that names a plugin class; empty for a library. */
    public static List<PluginPom> read(Path entry) {
        return find(entry).poms();
    }

    /** Whether {@code entry} is a plugin: exactly one of its poms names a class. */
    public static boolean declaresPlugin(Path entry) {
        return read(entry).size() == 1;
    }

    /** One pom's bytes and where they were read from, for a problem's sentence. */
    record Pom(String where, byte[] bytes) {
    }

    /** {@code pom} as a plugin's into {@code poms}, or a sentence into {@code problems}, or nothing for a library's. */
    static void parse(Pom pom, List<PluginPom> poms, List<String> problems) {
        Model model;
        try {
            model = new MavenXpp3Reader().read(new ByteArrayInputStream(pom.bytes()), false);
        } catch (IOException | XmlPullParserException | RuntimeException unreadable) {
            if (new String(pom.bytes(), StandardCharsets.UTF_8).contains(PROPERTY)) {
                problems.add(pom.where() + " names a plugin but does not parse: " + unreadable.getMessage());
            }
            return;
        }
        String raw = model.getProperties().getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) return;
        String pluginClass = plain(raw);
        String groupId = plain(model.getGroupId());
        if (groupId.isEmpty() && model.getParent() != null) groupId = plain(model.getParent().getGroupId());
        String artifactId = plain(model.getArtifactId());
        if (pluginClass.isEmpty()) {
            problems.add(pom.where() + "'s " + PROPERTY + " is " + raw.strip() + ", which nothing resolved: write the"
                    + " class's name itself");
        } else if (groupId.isEmpty() || artifactId.isEmpty()) {
            problems.add(pom.where() + " names " + pluginClass + " but has no literal groupId and artifactId,"
                    + " which are the plugin's id");
        } else {
            String name = plain(model.getName());
            poms.add(new PluginPom(new PluginCoordinate(groupId, artifactId), name.isEmpty() ? artifactId : name,
                    pluginClass));
        }
    }

    /** {@code text} stripped, or empty when absent or still an unresolved {@code ${…}}. */
    private static String plain(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.contains("${") ? "" : stripped;
    }

    /**
     * Every {@code META-INF/maven/<g>/<a>/pom.xml} in {@code entry}, or a class directory's module pom. A pom that
     * cannot be read is skipped on its own; an entry that is not a readable jar or directory has none.
     */
    private static List<Pom> poms(Path entry) {
        List<Pom> poms = new ArrayList<>();
        if (entry == null) return poms;
        if (Files.isDirectory(entry)) {
            Path maven = entry.resolve(MAVEN);
            if (Files.isDirectory(maven)) {
                try (Stream<Path> files = Files.walk(maven, 3)) {
                    for (Path file : files.filter(Files::isRegularFile)
                            .filter(f -> isMavenPom(entry.relativize(f).toString().replace('\\', '/'))).toList()) {
                        read(entry.relativize(file).toString(), file, poms);
                    }
                } catch (IOException | RuntimeException unreadable) {
                    // a directory that cannot be walked declares nothing
                }
            }
            Path module = moduleOf(entry);
            if (poms.isEmpty() && module != null) read(module.toString(), module, poms);
        } else if (Files.isRegularFile(entry)) {
            try (ZipFile zip = new ZipFile(entry.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry each = entries.nextElement();
                    if (each.isDirectory() || !isMavenPom(each.getName())) continue;
                    try (InputStream in = zip.getInputStream(each)) {
                        poms.add(new Pom(entry.getFileName() + "!/" + each.getName(), in.readAllBytes()));
                    } catch (IOException | RuntimeException unreadable) {
                        // this pom alone is skipped
                    }
                }
            } catch (IOException | RuntimeException notAJar) {
                // not a readable jar: no poms
            }
        }
        return poms;
    }

    private static void read(String where, Path file, List<Pom> poms) {
        try {
            poms.add(new Pom(where, Files.readAllBytes(file)));
        } catch (IOException | RuntimeException unreadable) {
            // this pom alone is skipped
        }
    }

    /** {@code <module>/pom.xml} for a {@code <module>/target/classes} directory, or null. */
    private static Path moduleOf(Path classes) {
        Path target = classes.toAbsolutePath().normalize().getParent();
        if (target == null || !classes.getFileName().toString().equals("classes")
                || !target.getFileName().toString().equals("target") || target.getParent() == null) {
            return null;
        }
        Path pom = target.getParent().resolve("pom.xml");
        return Files.isRegularFile(pom) ? pom : null;
    }

    /** {@code META-INF/maven/<g>/<a>/pom.xml}, and nothing deeper or shallower. */
    private static boolean isMavenPom(String name) {
        if (!name.startsWith(MAVEN) || !name.endsWith("/pom.xml")) return false;
        return name.substring(MAVEN.length()).split("/").length == 3;
    }
}
