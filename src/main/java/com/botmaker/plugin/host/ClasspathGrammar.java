package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.host.grammar.ValueGrammar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The value grammar of a process that is itself on its plugins' classpath: a running bot, which writes its own
 * during-a-run parameters (2026-11-04).
 *
 * <p>{@link PluginLoader} gives each plugin a loader of its own, so its classes are not the ones a bot's values are
 * made of. Here each plugin is made in the bot's own loader instead, so a value the bot holds is one the grammar can
 * write.
 */
public final class ClasspathGrammar {

    private ClasspathGrammar() {}

    /**
     * The grammar of every plugin {@code classpath} declares, each made in {@code loader}. A plugin that cannot be
     * made or cannot say its types is left out: a bot has no JavaFX, and JDK literals and enums need no plugin. Of two
     * plugins declaring one type, the first keeps it.
     */
    public static ValueGrammar of(List<String> classpath, ClassLoader loader) {
        List<PluginType<?>> types = new ArrayList<>();
        List<ComponentType<?>> components = new ArrayList<>();
        Set<String> claimed = new HashSet<>();
        for (String entry : classpath) {
            Path path = Path.of(entry);
            if (entry.isBlank() || !Files.exists(path)) continue;
            for (PluginPoms.PluginPom pom : PluginPoms.find(path).poms()) {
                try {
                    StudioPlugin plugin = (StudioPlugin) Class.forName(pom.pluginClass(), true, loader)
                            .getConstructor().newInstance();
                    List<PluginType<?>> offered = plugin.types();
                    List<ComponentType<?>> parts = plugin.componentTypes();
                    if (offered != null) {
                        for (PluginType<?> type : offered) {
                            if (type != null && claimed.add(type.type().getName())) types.add(type);
                        }
                    }
                    if (parts != null) parts.stream().filter(part -> part != null).forEach(components::add);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError unusable) {
                    // left out: what it would have written stays in memory for this run
                }
            }
        }
        return ValueGrammar.of(types, components);
    }
}
