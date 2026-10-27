package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Plugins built for a test: compiled against the real contract and named by a pom, the way a jar carries one. */
final class PluginFixtures {

    private PluginFixtures() {
    }

    /** The real contract's location, for {@code -cp}. */
    static String contractPath() {
        return StudioPlugin.class.getProtectionDomain().getCodeSource().getLocation().getPath();
    }

    /** {@code sources} (path → text) compiled against {@code classpath} into {@code dir/classes}. */
    static Path compile(Path dir, String classpath, Map<String, String> sources) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "no javac in this JRE");
        List<String> args = new ArrayList<>();
        if (classpath != null) args.addAll(List.of("-cp", classpath));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        args.addAll(List.of("-d", classes.toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = dir.resolve("src").resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        // A fixture that stops compiling is this test failing, never a reason to skip it.
        assertEquals(0, javac.run(null, null, null, args.toArray(String[]::new)), "the fixture compiles");
        return classes;
    }

    /** {@code p.<simpleName>}: a {@code public final} plugin declaring nothing, as a source map entry. */
    static Map.Entry<String, String> plugin(String simpleName) {
        return Map.entry("p/" + simpleName + ".java", "package p; import com.botmaker.plugin.api.*;"
                + " public final class " + simpleName + " extends DeclaredPlugin {"
                + " public " + simpleName + "() { super(StudioPlugin.declare()); } }");
    }

    /** Writes the pom Maven would copy into the jar, naming {@code pluginClass}, under {@code classes}. */
    static Path declare(Path classes, String artifactId, String pluginClass) throws IOException {
        Path pom = classes.resolve("META-INF/maven/test/" + artifactId + "/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, pom("test", artifactId, pluginClass, null));
        return classes;
    }

    /** A pom of {@code groupId:artifactId}, naming {@code pluginClass} when given and pinning {@code contract} when given. */
    static String pom(String groupId, String artifactId, String pluginClass, String contract) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><groupId>" + groupId + "</groupId>"
                + "<artifactId>" + artifactId + "</artifactId><name>" + artifactId + " plugin</name>"
                + (pluginClass == null ? "" : "<properties><botmaker.plugin>" + pluginClass
                + "</botmaker.plugin></properties>")
                + (contract == null ? "" : "<dependencies><dependency><groupId>com.github.BotMakerDev</groupId>"
                + "<artifactId>botmaker-studio-api</artifactId>\n      <version>" + contract
                + "</version></dependency></dependencies>")
                + "</project>";
    }

    /** A jar of {@code entries}; each value's chars are written as bytes one-for-one (ISO-8859-1). */
    static Path jar(Path file, Map<String, String> entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.ISO_8859_1));
                out.closeEntry();
            }
        }
        return file;
    }

    /** A class file's bytes as the one-for-one string {@link #jar} writes back. */
    static String bytes(Path classFile) throws IOException {
        return new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
    }
}
