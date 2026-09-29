package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
 * <h2>Nothing here reflects on a plugin's own classes</h2>
 *
 * <p>{@link ServiceLoader#load(Class, ClassLoader)} hands back instances <em>typed as</em>
 * {@link StudioPlugin}, so every later call — {@code id()}, {@code catalog(pin)}, {@code valueTypes()} — is
 * an ordinary javac-checked one. Reflection happens once, inside {@code ServiceLoader}, to invoke a no-arg
 * constructor. A host never names a plugin's implementation class, and the rule that keeps that safe is
 * already true of this project: <b>every type crossing the boundary is a contract type or a JDK type.</b>
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
    private final List<StudioPlugin> plugins;

    private PluginLoader(URLClassLoader loader, List<StudioPlugin> plugins) {
        this.loader = loader;
        this.plugins = plugins;
    }

    /**
     * One plugin that did not load, and why.
     *
     * <p>The cause is kept whole rather than rendered, because the two readers want different amounts of it:
     * a dialog wants one line, a {@code --verbose} CLI wants the stack. {@link #describe()} is the one line.
     *
     * @param provider the implementation class named by the services file, or a description of the failure
     *                 when the name is what could not be read
     */
    public record PluginFailure(String provider, Throwable cause) {

        public PluginFailure {
            provider = provider == null || provider.isBlank() ? "a plugin" : provider;
        }

        /**
         * {@code <provider> — <the cause's own message>}, which is what a user is shown. A
         * {@link NoClassDefFoundError} names only the class that was missing, so the line says what that
         * means: {@code a plugin — p/Helper is not on the classpath}.
         *
         * <p><b>The missing class is looked for down the cause chain, not only at the top</b> (2026-09-21).
         * Up to JDK 25 {@code ServiceLoader} let a {@code LinkageError} out of {@code Class.forName} raw, so
         * the missing superclass <i>was</i> the top exception. From JDK 27 it wraps it in a
         * {@code ServiceConfigurationError} — which is better, since that one names the provider line the
         * raw error never said — and the class a user actually has to put back was suddenly two words in a
         * cause nobody prints. Same line on both.
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
            String message = cause == null ? "" : cause.getMessage();
            return provider + " — "
                    + (message == null || message.isBlank()
                    ? (cause == null ? "did not load" : cause.getClass().getSimpleName())
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
     * <p>Null rather than an empty loader on purpose: the caller's fallback is the bundled plugin set, and a
     * project whose classpath resolved to nothing must get the bundled menus rather than none. Every failure
     * here is one of those — an unresolvable pin, a jar with no services file, a plugin whose constructor
     * throws — and each is logged and answered the same way.
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
     * <h2>Isolation is per provider, and until 2026-09-06 it was per classpath</h2>
     *
     * <p>The loop used to be one {@code for} over {@link ServiceLoader} inside one {@code try}, so the first
     * provider that would not load <b>ended the iteration</b> and every plugin after it in the services file
     * was silently absent. With one plugin in the world that was invisible; with two it is the difference
     * between "the SDK is broken" and "nothing works".
     *
     * <p>So the failure is caught in two places, because a plugin can fail at two moments and they are not
     * the same moment. <b>Advancing the iterator</b> is where {@code Class.forName} runs, which is where a
     * missing superclass throws {@link NoClassDefFoundError} — the ordinary shape of a plugin whose own
     * dependency is not on the classpath. <b>{@code Provider.get()}</b> is where the no-arg constructor runs,
     * which is where a constructor that links an {@code optional} dependency throws — the shape that shipped
     * as SDK v1.1.5. The iterator keeps going after either.
     *
     * <p>{@code LinkageError} rather than {@code Error}: a broken plugin must not make an
     * {@link OutOfMemoryError} look like a missing services file.
     *
     * <h2>A plugin built for a newer contract is refused before it is constructed (2026-09-28)</h2>
     *
     * <p>Each entry that declares a plugin is read by {@link ContractLinks#missing} against the host's own
     * contract, and a provider from an entry that links something the host lacks is a failure,
     * <i>built for a newer Studio: needs …</i>, rather than a {@link NoSuchMethodError} the first time the
     * missing member is reached. The entry stays on the classpath, because another plugin may depend on its
     * classes; only its own providers are skipped.
     *
     * <p><b>And for an older one, named from its services file (2026-09-29).</b> The first real mismatch ran the
     * other way — an SDK built on a contract that has since deleted what it extends — and there the provider
     * never resolves: {@code ServiceLoader} throws for a missing contract class, naming neither the provider nor
     * the contract. So a mismatched entry's providers are reported by the names its services file lists, with
     * {@link ContractLinks.Direction which side is behind}, and the loader's own error for them is dropped.
     */
    public static Loaded openReporting(List<String> classpath) {
        if (classpath == null || classpath.isEmpty()) return new Loaded(null, List.of());
        URL[] urls = urlsOf(classpath);
        if (urls.length == 0) return new Loaded(null, List.of());

        URLClassLoader loader = new Inverted(urls, PluginLoader.class.getClassLoader());
        List<StudioPlugin> found = new ArrayList<>();
        List<PluginFailure> failures = new ArrayList<>();
        Map<Path, ContractLinks.ContractMismatch> mismatched = mismatches(classpath);
        Set<String> unlinked = new HashSet<>();
        for (Map.Entry<Path, ContractLinks.ContractMismatch> each : mismatched.entrySet()) {
            // Reported from the services file, by name, before anything is loaded: a provider that extends a
            // contract type the host no longer has cannot even be resolved, and the loader's own error for it
            // names neither the provider nor the contract.
            for (String name : providerNames(each.getKey())) failures.add(new PluginFailure(name, each.getValue()));
            for (ContractLinks.Link link : each.getValue().missing()) unlinked.add(link.owner());
        }

        Iterator<ServiceLoader.Provider<StudioPlugin>> providers =
                ServiceLoader.load(StudioPlugin.class, loader).stream().iterator();
        while (true) {
            ServiceLoader.Provider<StudioPlugin> provider;
            try {
                if (!providers.hasNext()) break;
                provider = providers.next();
            } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                NoClassDefFoundError missing = PluginFailure.missingClass(e);
                if (missing != null && !mismatched.isEmpty() && (unlinked.contains(missing.getMessage())
                        || missing.getMessage().startsWith(ContractLinks.CONTRACT))) {
                    continue;                                   // the entry's mismatch, reported above
                }
                // The services file named a class that could not be resolved. ServiceLoader has already
                // consumed that line, so the next hasNext() reads the next one — which is what makes this a
                // `continue` rather than a `break`, and what `a_broken_plugin_does_not_cost_the_others`
                // holds. Which line it was is not recoverable here: a ServiceConfigurationError names the
                // provider in its message, but a LinkageError (the provider's superclass missing, which is
                // the archetype's shape) names only the class that was missing — `p/Helper`, not
                // `p.BrokenPlugin`. That is still the thing to fix, so it is what the failure says, and
                // PluginFailure.describe reads it wherever the running JDK put it: raw up to JDK 25, inside
                // a ServiceConfigurationError from JDK 27.
                failures.add(new PluginFailure(null, e));
                continue;
            }
            if (mismatched.containsKey(entryOf(provider))) continue;                        // reported above
            try {
                found.add(provider.get());
            } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                failures.add(new PluginFailure(provider.type().getName(), e));
            }
        }

        for (PluginFailure failure : failures) {
            System.err.println("Warning: could not load a plugin from the project classpath: "
                    + failure.describe());
        }
        if (found.isEmpty()) {
            close(loader);
            return new Loaded(null, failures);
        }
        return new Loaded(new PluginLoader(loader, List.copyOf(found)), failures);
    }

    public List<StudioPlugin> plugins() {
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

    /**
     * Each classpath entry that declares a plugin and links something the host's contract lacks, with what it
     * lacks and which side is behind. Entries that declare no plugin are not read: a library's links are its
     * plugin's business.
     */
    private static Map<Path, ContractLinks.ContractMismatch> mismatches(List<String> classpath) {
        Map<Path, ContractLinks.ContractMismatch> mismatched = new LinkedHashMap<>();
        ClassLoader contract = StudioPlugin.class.getClassLoader();
        for (String entry : classpath) {
            if (entry == null || entry.isBlank()) continue;
            Path path;
            try {
                path = Path.of(entry).toAbsolutePath().normalize();
            } catch (RuntimeException notAPath) {
                continue;                                   // urlsOf has already said so
            }
            if (!ContractLinks.declaresPlugin(path)) continue;
            List<ContractLinks.Link> missing = ContractLinks.missing(path, contract);
            if (!missing.isEmpty()) mismatched.put(path, ContractLinks.mismatch(path, missing));
        }
        return mismatched;
    }

    /** The provider class names {@code entry}'s services file lists, comments and blanks dropped. */
    static List<String> providerNames(Path entry) {
        String text;
        try {
            if (Files.isDirectory(entry)) {
                text = Files.readString(entry.resolve(ContractLinks.SERVICES));
            } else {
                try (ZipFile zip = new ZipFile(entry.toFile())) {
                    ZipEntry services = zip.getEntry(ContractLinks.SERVICES);
                    if (services == null) return List.of();
                    try (InputStream in = zip.getInputStream(services)) {
                        text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return text.lines()
                .map(line -> line.replaceFirst("#.*", "").strip())
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /** The classpath entry {@code provider}'s class was read from, or null when it cannot be told. */
    private static Path entryOf(ServiceLoader.Provider<StudioPlugin> provider) {
        try {
            CodeSource source = provider.type().getProtectionDomain().getCodeSource();
            return source == null ? null : Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private static void close(URLClassLoader loader) {
        try {
            loader.close();
        } catch (Exception e) {
            System.err.println("Warning: could not close the plugin classloader: " + e);
        }
    }

    private static URL[] urlsOf(List<String> classpath) {
        List<URL> urls = new ArrayList<>(classpath.size());
        for (String entry : classpath) {
            if (entry == null || entry.isBlank()) continue;
            try {
                urls.add(new File(entry).toURI().toURL());
            } catch (MalformedURLException e) {
                // A classpath entry that is not a path is not a reason to lose the rest of the classpath.
                System.err.println("Warning: skipping classpath entry " + entry + ": " + e.getMessage());
            }
        }
        return urls.toArray(URL[]::new);
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
