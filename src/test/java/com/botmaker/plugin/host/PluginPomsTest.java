package com.botmaker.plugin.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.botmaker.plugin.host.PluginFixtures.jar;
import static com.botmaker.plugin.host.PluginFixtures.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which plugin an entry is comes from its poms alone: the coordinate, the name and the class. */
class PluginPomsTest {

    @Test
    void a_pom_naming_a_class_gives_its_coordinate_name_and_class() {
        PluginPoms.Found found = parse(pom("com.example", "notes", "com.example.Notes", null));

        assertEquals(List.of(new PluginPoms.PluginPom(new PluginCoordinate("com.example", "notes"), "notes plugin",
                "com.example.Notes")), found.poms());
        assertEquals(List.of(), found.problems());
    }

    @Test
    void the_groupId_may_come_from_the_parent_and_the_name_falls_back_to_the_artifactId() {
        PluginPoms.PluginPom pom = parse("<project><parent><groupId>com.example</groupId>"
                + "<artifactId>parent</artifactId><version>1</version></parent><artifactId>notes</artifactId>"
                + "<properties><botmaker.plugin>com.example.Notes</botmaker.plugin></properties></project>")
                .poms().getFirst();

        assertEquals(new PluginCoordinate("com.example", "notes"), pom.coordinate());
        assertEquals("notes", pom.name());
    }

    @Test
    void a_library_pom_names_nothing_and_says_nothing() {
        assertEquals(new PluginPoms.Found(List.of(), List.of()), parse(pom("g", "lib", null, null)));
        assertEquals(new PluginPoms.Found(List.of(), List.of()), parse("<project><artifactId>"));
    }

    /** A plugin must never vanish without a word: a pom that tries to name one and cannot is a problem. */
    @Test
    void a_pom_that_names_a_plugin_but_cannot_be_used_is_a_problem() {
        assertEquals(List.of("x's botmaker.plugin is ${plugin.class}, which nothing resolved: write the class's"
                + " name itself"), parse(pom("g", "a", "${plugin.class}", null)).problems());
        assertEquals(List.of("x names p.A but has no literal groupId and artifactId, which are the plugin's id"),
                parse(pom("${group}", "a", "p.A", null)).problems());
        assertTrue(parse("<project><properties><botmaker.plugin>p.A</botmaker.plugin>").problems().getFirst()
                .startsWith("x names a plugin but does not parse: "));
    }

    @Test
    void only_poms_at_the_maven_depth_count(@TempDir Path dir) throws IOException {
        Path entry = jar(dir.resolve("x.jar"), Map.of(
                "META-INF/maven/g/a/pom.xml", pom("g", "a", "p.A", null),
                "META-INF/maven/g/a/extra/pom.xml", pom("g", "deep", "p.Deep", null),
                "pom.xml", pom("g", "root", "p.Root", null)));

        assertEquals(List.of("p.A"), PluginPoms.read(entry).stream().map(PluginPoms.PluginPom::pluginClass).toList());
        assertTrue(PluginPoms.declaresPlugin(entry));
    }

    @Test
    void a_target_classes_directory_reads_its_modules_pom(@TempDir Path dir) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("module/target/classes"));
        Files.writeString(dir.resolve("module/pom.xml"), pom("g", "module", "p.Module", null));

        assertEquals(List.of(new PluginCoordinate("g", "module")),
                PluginPoms.read(classes).stream().map(PluginPoms.PluginPom::coordinate).toList());
        assertFalse(PluginPoms.declaresPlugin(dir.resolve("module")), "the module folder itself is no entry");
    }

    private static PluginPoms.Found parse(String text) {
        List<PluginPoms.PluginPom> poms = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        PluginPoms.parse(new PluginPoms.Pom("x", text.getBytes(StandardCharsets.UTF_8)), poms, problems);
        return new PluginPoms.Found(poms, problems);
    }
}
