package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.plugin.api.palette.Palette;

import java.util.ArrayList;
import java.util.List;

/**
 * A plugin's palette, as a host reads it: the plugin's own {@link StudioPlugin#catalog()} when it builds one
 * by hand, otherwise every {@link Palette} class in the jar the plugin was loaded from.
 *
 * <p>The annotation is the declaration, so no plugin lists its classes. Discovery reads only the plugin's
 * own jar (or class directory), never the rest of the classpath: a class another jar annotates is that jar's
 * plugin's to offer. It links nothing ({@link JarClasses}).
 *
 * <p>Like {@link PaletteCatalog#of}, it degrades rather than throws: an unreadable jar or an unloadable
 * class is a line in {@link PaletteCatalog#problems()}.
 */
public final class Palettes {

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
        Class<?>[] classes = JarClasses.naming(anchor, Palette.class, problems).stream()
                .filter(type -> type.isAnnotationPresent(Palette.class))
                .toArray(Class<?>[]::new);
        PaletteCatalog found = PaletteCatalog.of(classes);
        problems.addAll(found.problems());
        return new PaletteCatalog(found.facades(), problems);
    }
}
