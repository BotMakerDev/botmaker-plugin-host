package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;

import java.nio.file.Path;

/**
 * One plugin {@link PluginLoader} constructed, with what its pom said and the classpath entry it came from — so
 * no host has to work the jar out again from the instance's {@code CodeSource}.
 *
 * @param coordinate its identity, the declaring pom's {@code groupId:artifactId}
 * @param name       the pom's {@code <name>}, or the artifactId when it has none: what a user reads
 * @param jar        the classpath entry, a jar or a class directory, absolute and normalised
 * @param plugin     the instance
 */
public record LoadedPlugin(PluginCoordinate coordinate, String name, Path jar, StudioPlugin plugin) {
}
