package com.botmaker.plugin.host;

import com.botmaker.plugin.api.DeclaredPlugin;
import com.botmaker.plugin.api.StudioPlugin;

import java.io.Closeable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;

/**
 * The plugins on <em>one project's</em> resolved classpath, loaded from that project's own jars.
 *
 * <p>This is what makes the inversion's rule — <em>a bot gets its answers from its own version</em> — true
 * of the palette and the value vocabulary rather than only of the generated source. A project pins an SDK;
 * Maven resolves that jar; this loads the {@link StudioPlugin} out of <em>that</em> jar and not out of the
 * one the host itself was compiled against.
 *
 * <p>It lived in {@code botmaker-studio} until 2026-08-28. It moved because Studio stopped being the only
 * host: the CLI's {@code validate} and {@code run}, and the plugin registry's CI, all have to load a plugin
 * <em>exactly</em> as Studio does, and the delegation split below is the last code in this project that
 * should exist in two copies.
 *
 * <h2>A plugin is its jar, and its pom names it (2026-10-27)</h2>
 *
 * <p>Until 2026-10-27 a plugin was whatever a {@code META-INF/services} file listed, and that file is a list: a
 * jar could declare two plugins, and two jars could answer one {@code id()}, with nothing to say so. Now each
 * entry's poms are read ({@link PluginPoms}) before any of its code runs. An entry whose poms name no class is a
 * library; one whose poms name two is refused; two entries with one coordinate are refused; an entry built
 * against a contract this host lacks is refused ({@link ContractLinks}). What is left is constructed: the named
 * class, which must be in that entry, extend {@link DeclaredPlugin} directly, be {@code public final} and have a
 * public no-argument constructor. Each answer is a {@link LoadedPlugin}, so the coordinate, name and jar travel
 * with the instance.
 *
 * <h2>Nothing here reflects on a plugin beyond its constructor</h2>
 *
 * <p>The instance is typed as {@link StudioPlugin}, so every later call is an ordinary javac-checked one. A host
 * never names a plugin's implementation class, and the rule that keeps that safe is already true of this
 * project: <b>every type crossing the boundary is a contract type or a JDK type.</b>
 *
 * <h2>The delegation split is the load-bearing part, and it is not the JDK default</h2>
 *
 * <p>A plain {@link URLClassLoader} is parent-first for everything, which would resolve the plugin class
 * from the host's <em>own</em> compile dependency — the bundled SDK — and defeat the whole point: the
 * pinned SDK is not the bundled SDK. So the split is inverted, in one direction only:
 *
 * <ul>
 *   <li><b>parent-first</b> for {@code com.botmaker.plugin.api.**} and the platform namespaces. A contract
 *       class must be the <em>same</em> {@link Class} object on both sides, or handing a
 *       {@code PaletteCatalog} back across the boundary throws {@link ClassCastException} against a type
 *       whose name is identical to the one it was expected to be — the least diagnosable failure available.
 *       This is also why {@code botmaker-studio-api} is {@code provided} in this module's pom: a
 *       transitive second copy of the contract is the one thing that can make that identity false.
 *   <li><b>child-first</b> for everything else, notably {@code com.botmaker.sdk.**}, falling back to the
 *       parent when the child has no such class.
 * </ul>
 *
 * <p>While a host still declares a compile dependency on the SDK, two SDK class-spaces are live at once —
 * the host's own and this loader's. <b>They must never exchange an SDK type.</b> They do not today: every
 * Studio consumer of a catalog entry reaches it through {@code simpleName()} / {@code qualifiedName()} /
 * {@code offered()}, and nothing compares a {@code Class<?>} across the two. Keeping it that way is a
 * condition of this class working, not a tidiness preference.
 */
public final class PluginLoader implements Closeable {

    /**
     * Namespaces the parent answers first. The contract is here for the identity reason above; the platform
     * three are here because a child copy of {@code java.**} is either impossible or a disaster, and because
     * a plugin bundling its own JavaFX would otherwise get a second toolkit in a process that has one.
     */
    private static final List<String> PARENT_FIRST =
            List.of("com.botmaker.plugin.api.", "java.", "javax.", "javafx.", "jdk.", "sun.");

    private final URLClassLoader loader;
    private final List<LoadedPlugin> plugins;

    private PluginLoader(URLClassLoader loader, List<LoadedPlugin> plugins) {
        this.loader = loader;
        this.plugins = plugins;
    }

    /**
     * One plugin that did not load, and why.
     *
     * <p>The cause is kept whole rather than rendered, because the two readers want different amounts of it:
     * a dialog wants one line, a {@code --verbose} CLI wants the stack. {@link #describe()} is the one line.
     *
     * @param provider the plugin's class as its pom names it, or its jar when the class is not what is wrong
     */
    public record PluginFailure(String provider, Throwable cause) {

        public PluginFailure {
            provider = provider == null || provider.isBlank() ? "a plugin" : provider;
        }

        /**
         * {@code <provider> — <the cause's own message>}, which is what a user is shown. A
         * {@link NoClassDefFoundError} names only the class that was missing, so the line says what that
         * means: {@code p.Broken — p/Helper is not on the classpath}.
         *
         * <p><b>The missing class is looked for down the cause chain, not only at the top</b> (2026-09-21): a
         * wrapper names the plugin, and the class a user actually has to put back is a cause nobody prints.
         */
        public String describe() {
            NoClassDefFoundError missing = missingClass(cause);
            if (missing != null && missing.getMessage().startsWith(ContractLinks.CONTRACT)) {
                // A contract class the host has not got: something on the classpath was built for another
                // Studio, which is what to fix — not a jar to put back.
                return provider + " — built for a different Studio: it uses "
                        + missing.getMessage().substring(missing.getMessage().lastIndexOf('/') + 1)
                        + ", which this Studio has not got";
            }
            if (missing != null) return provider + " — " + missing.getMessage() + " is not on the classpath";
            // "Provider p.X could not be instantiated" says nothing the provider's name does not: what its
            // constructor threw is what to fix (2026-10-19).
            Throwable said = cause instanceof ServiceConfigurationError wrapped && wrapped.getCause() != null
                    ? wrapped.getCause() : cause;
            String message = said == null ? "" : said.getMessage();
            return provider + " — "
                    + (message == null || message.isBlank()
                    ? (said == null ? "did not load" : said.getClass().getSimpleName())
                    : message);
        }

        /**
         * The first {@link NoClassDefFoundError} with a message at or under {@code throwable}, or null.
         *
         * <p>Bounded and cycle-safe: a cause chain may loop (a {@code Throwable} is allowed to be its own
         * cause), and a plugin that will not load must not cost the host the thread that was reporting it.
         */
        private static NoClassDefFoundError missingClass(Throwable throwable) {
            for (int depth = 0; throwable != null && depth < 8; depth++) {
                if (throwable instanceof NoClassDefFoundError error
                        && error.getMessage() != null && !error.getMessage().isBlank()) {
                    return error;
                }
                Throwable next = throwable.getCause();
                throwable = next == throwable ? null : next;
            }
            return null;
        }
    }

    /**
     * What {@link #openReporting} answers: the loader, and everything that did not load.
     *
     * <p>They are separate because they fail separately — a classpath can produce two working plugins and one
     * broken one, and before 2026-09-06 that state produced <em>zero</em> plugins and one line on stderr.
     */
    public record Loaded(PluginLoader loader, List<PluginFailure> failures) {

        public Loaded {
            failures = List.copyOf(failures);
        }
    }

    /**
     * Loads every plugin declared on {@code classpath}, or {@code null} when there is nothing to load or
     * nothing loadable.
     *
     * <p>Null rather than an empty loader on purpose: a host holds no classloader for a project that has no
     * plugin, so there is nothing to close.
     *
     * <p>Use {@link #openReporting} where the failures are worth telling somebody about; this is the same
     * pass with them dropped.
     */
    public static PluginLoader open(List<String> classpath) {
        return openReporting(classpath).loader();
    }

    /**
     * {@link #open}, and what did not load beside it.
     *
     * <p><b>Isolation is per plugin.</b> Each refusal and each failure to construct is one
     * {@link PluginFailure}, and every other plugin still loads. A class that cannot be resolved throws
     * {@link NoClassDefFoundError} — the ordinary shape of a plugin whose own dependency is not on the classpath —
     * and a constructor that links an {@code optional} dependency throws while constructing (SDK v1.1.5); both are
     * caught. {@code LinkageError} rather than {@code Error}: a broken plugin must not make an
     * {@link OutOfMemoryError} look like a missing class.
     *
     * <p><b>A plugin built for another contract is refused before it is constructed (2026-09-28)</b>, with
     * {@link ContractLinks.Direction which side is behind} (2026-09-29), rather than a {@link NoSuchMethodError}
     * the first time the missing member is reached. The entry stays on the classpath, because another plugin may
     * depend on its classes; only it is not constructed.
     *
     * <p>Nothing is printed: every failure is in the answer, and the host shows it.
     */
    public static Loaded openReporting(List<String> classpath) {
        if (classpath == null || classpath.isEmpty()) return new Loaded(null, List.of());
        List<Path> entries = entriesOf(classpath);
        if (entries.isEmpty()) return new Loaded(null, List.of());

        List<PluginFailure> failures = new ArrayList<>();
        List<Declared> declared = declared(entries, failures);
        if (declared.isEmpty()) return new Loaded(null, failures);

        URLClassLoader loader = new Inverted(urlsOf(entries), PluginLoader.class.getClassLoader());
        List<LoadedPlugin> found = new ArrayList<>();
        try {
            for (Declared each : declared) {
                String name = each.pom().pluginClass();
                try {
                    found.add(new LoadedPlugin(each.pom().coordinate(), each.pom().name(), each.entry(),
                            construct(loader, each.entry(), name)));
                } catch (InvocationTargetException e) {
                    failures.add(new PluginFailure(name, e.getCause() == null ? e : e.getCause()));
                } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                    failures.add(new PluginFailure(name, e));
                }
            }
        } catch (Error fatal) {
            close(loader);                                  // never leave the jars held behind an OutOfMemoryError
            throw fatal;
        }
        if (found.isEmpty()) {
            close(loader);
            return new Loaded(null, failures);
        }
        return new Loaded(new PluginLoader(loader, List.copyOf(found)), failures);
    }

    /** Every plugin this loader constructed, each with its coordinate, name and jar. */
    public List<LoadedPlugin> plugins() {
        return plugins;
    }

    /**
     * The loader the plugins were read from — what a host resolves a bot's call on, so the
     * {@code Executable} it hands a plugin's editor names that plugin's own classes. Closed with this.
     */
    public ClassLoader classLoader() {
        return loader;
    }

    /**
     * Releases the jars. Required rather than housekeeping: an open {@link URLClassLoader} holds every jar it
     * read, and on Windows a held jar cannot be replaced — so a project left unclosed makes the next
     * <em>Manage Libraries</em> resolve fail on a file lock.
     */
    @Override
    public void close() {
        close(loader);
    }

    /**
     * Whether the parent classloader answers for {@code name} before the project's own jars are consulted.
     *
     * <p>Package-private rather than buried in {@link Inverted} so it can be asserted directly. It is the
     * one decision in this class with no visible symptom when it is wrong — a class resolved from the wrong
     * side still loads, and fails much later as a {@link ClassCastException} between two identically named
     * types.
     */
    static boolean parentFirst(String name) {
        for (String prefix : PARENT_FIRST) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** A classpath entry whose one pom names a plugin, about to be constructed. */
    private record Declared(Path entry, PluginPoms.PluginPom pom) {
    }

    /**
     * The entries that declare exactly one plugin, each coordinate once, each linking only what the host's
     * contract has; every other declaring entry is a failure, worded by what is wrong with it.
     */
    private static List<Declared> declared(List<Path> entries, List<PluginFailure> failures) {
        List<Declared> declared = new ArrayList<>();
        Map<PluginCoordinate, Path> seen = new HashMap<>();
        ClassLoader contract = StudioPlugin.class.getClassLoader();
        for (Path entry : entries) {
            PluginPoms.Found found = PluginPoms.find(entry);
            for (String problem : found.problems()) {
                failures.add(new PluginFailure(entry.getFileName().toString(), new IllegalStateException(problem)));
            }
            List<PluginPoms.PluginPom> poms = found.poms();
            if (poms.isEmpty()) continue;
            if (poms.size() > 1) {
                failures.add(new PluginFailure(entry.getFileName().toString(), new IllegalStateException(
                        "its poms name " + poms.size() + " plugins (" + String.join(", ", poms.stream()
                                .map(PluginPoms.PluginPom::pluginClass).toList())
                                + "); one jar is one plugin, so none of them is loaded")));
                continue;
            }
            PluginPoms.PluginPom pom = poms.getFirst();
            Path first = seen.get(pom.coordinate());
            if (first != null) {
                failures.add(new PluginFailure(pom.pluginClass(), new IllegalStateException(
                        entry.getFileName() + " is " + pom.coordinate() + ", which " + first.getFileName()
                                + " already is")));
                continue;
            }
            List<ContractLinks.Link> missing = ContractLinks.missing(entry, contract);
            if (!missing.isEmpty()) {
                // Not claimed: a later entry with this coordinate that this host can load is the one to keep.
                failures.add(new PluginFailure(pom.pluginClass(), ContractLinks.mismatch(entry, missing)));
                continue;
            }
            seen.put(pom.coordinate(), entry);
            declared.add(new Declared(entry, pom));
        }
        return declared;
    }

    /**
     * The plugin {@code name} names, constructed: the class must be {@code entry}'s own, a direct
     * {@code public final} subclass of {@link DeclaredPlugin}, with a public no-argument constructor.
     */
    private static StudioPlugin construct(ClassLoader loader, Path entry, String name)
            throws ReflectiveOperationException {
        Class<?> type;
        try {
            type = Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the pom's " + PluginPoms.PROPERTY + " names " + name
                    + ", which is not in " + entry.getFileName(), e);
        }
        if (!entry.equals(entryOf(type))) {
            throw new IllegalStateException("the pom's " + PluginPoms.PROPERTY + " names " + name
                    + ", which is not in " + entry.getFileName() + ": a plugin's class is in its own jar");
        }
        if (type.getSuperclass() != DeclaredPlugin.class) {
            throw new IllegalStateException(name + " does not extend DeclaredPlugin");
        }
        int modifiers = type.getModifiers();
        if (!Modifier.isPublic(modifiers) || !Modifier.isFinal(modifiers)) {
            throw new IllegalStateException(name + " is not a public final class");
        }
        return (StudioPlugin) type.getConstructor().newInstance();
    }

    /** The classpath entry {@code type} was read from, or null when it cannot be told. */
    private static Path entryOf(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            return source == null ? null : Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Each entry as an absolute path. A blank or unparseable one is skipped: a classpath entry that is not a path
     * is not a reason to lose the rest of the classpath, and no plugin can be read from it.
     */
    private static List<Path> entriesOf(List<String> classpath) {
        Set<Path> entries = new LinkedHashSet<>();          // an entry listed twice is one entry, not a duplicate plugin
        for (String entry : classpath) {
            if (entry == null || entry.isBlank()) continue;
            try {
                entries.add(Path.of(entry).toAbsolutePath().normalize());
            } catch (InvalidPathException e) {
                // skipped, as above
            }
        }
        return List.copyOf(entries);
    }

    private static URL[] urlsOf(List<Path> entries) {
        List<URL> urls = new ArrayList<>(entries.size());
        for (Path entry : entries) {
            try {
                urls.add(entry.toUri().toURL());
            } catch (MalformedURLException e) {
                // A path the JDK made cannot be a malformed URL; nothing to load from it if it were.
            }
        }
        return urls.toArray(URL[]::new);
    }

    private static void close(URLClassLoader loader) {
        try {
            loader.close();
        } catch (Exception e) {
            // Closing releases file handles; one that will not close holds a jar until exit, and says so nowhere
            // a user could act on.
        }
    }

    /** The inverted-delegation loader described in the class javadoc. */
    private static final class Inverted extends URLClassLoader {

        Inverted(URL[] urls, ClassLoader parent) {
            super("botmaker-plugins", urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findAnywhere(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        private Class<?> findAnywhere(String name) throws ClassNotFoundException {
            if (parentFirst(name)) return super.loadClass(name, false);
            try {
                return findClass(name);
            } catch (ClassNotFoundException notInTheProject) {
                // Everything a plugin needs that its own jars do not carry — the JDK's wider surface, and
                // for now the libraries the host and the SDK happen to share.
                return super.loadClass(name, false);
            }
        }
    }
}
