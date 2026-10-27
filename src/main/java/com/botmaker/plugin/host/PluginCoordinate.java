package com.botmaker.plugin.host;

/**
 * A plugin's identity: the {@code groupId:artifactId} of the pom that names it (2026-10-27). It is what the
 * catalog lists, what a project's pom pins and what a release moves, so a plugin has no second id to keep in
 * step with it.
 */
public record PluginCoordinate(String groupId, String artifactId) {

    public PluginCoordinate {
        if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("A plugin coordinate needs a groupId and an artifactId");
        }
        groupId = groupId.strip();
        artifactId = artifactId.strip();
    }

    /** {@code groupId:artifactId} back into a coordinate, or null when it is not one. */
    public static PluginCoordinate parse(String text) {
        if (text == null) return null;
        int colon = text.indexOf(':');
        if (colon <= 0 || colon == text.length() - 1 || text.indexOf(':', colon + 1) >= 0) return null;
        return new PluginCoordinate(text.substring(0, colon), text.substring(colon + 1));
    }

    /** {@code groupId:artifactId}. */
    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }
}
