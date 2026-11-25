// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.plugin.host;

import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.MarkedReturns;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.java.parse.JavaLevel;
import com.botmaker.plugin.api.PluginState;
import com.botmaker.plugin.api.managed.RunHostValues;
import com.botmaker.plugin.api.source.ManagedMethod;
import com.botmaker.plugin.api.source.ManagedValue;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A run's half of a plugin's values of this PC ({@link RunHostValues}, 2026-11-15): each value read from, and written
 * into, the plugin's holder class in its folder of this PC, by the grammar of the bot's own JVM, under the folder's
 * {@code .lock} the host's writes take too. A write is not compiled and not committed: the host commits what a run
 * changed when the run ends.
 *
 * <p>Built by the run's agent on the bot's own loader ({@link #serve}), so the grammar is the one the bot's plugins
 * were built against. The folder's layout is the core's {@code HostValues}: {@code plugins/<group>/<artifact>/
 * src/main/java/<group>/<artifact>/<Holder>.java}, each part {@link PluginState#part}, BotMaker's older groupId kept
 * under the current one.
 */
public final class RunHostFolders implements RunHostValues.Service {

    /** The file both the host and a run lock a folder by. */
    static final String LOCK_FILE = ".lock";
    private static final int MOVE_TRIES = 5;
    private static final Duration MOVE_WAIT = Duration.ofMillis(40);
    private static final String SOURCES = "src/main/java";
    /** BotMaker's groupId, and the one it had before 2026-10-05: one plugin's folder. */
    private static final String BOTMAKER_GROUP = "com.github.BotMakerDev";
    private static final String OLDER_GROUP = "com.github.LiQiyeDev";
    /** One writer at a time in this JVM: a file lock is the process's, and refuses a second taker in it. */
    private static final Object WRITING = new Object();

    private final Path root;
    private final Function<Path, Optional<PluginCoordinate>> owners;
    private final Supplier<ValueGrammar> grammar;

    /**
     * @param root    the folder the plugins' folders are under ({@code UserDirs.config()})
     * @param owners  the plugin a jar or class folder declares, or empty
     * @param grammar the grammar values are read and written by, asked on first use
     */
    RunHostFolders(Path root, Function<Path, Optional<PluginCoordinate>> owners, Supplier<ValueGrammar> grammar) {
        this.root = root;
        this.owners = owners;
        this.grammar = grammar;
    }

    /** Serves this run's values of this PC from the folders under {@code root}. Called by the run's agent. */
    public static void serve(Path root) {
        ClassLoader loader = RunHostFolders.class.getClassLoader();
        ValueGrammar[] built = new ValueGrammar[1];
        RunHostValues.serve(new RunHostFolders(root, RunHostFolders::declaredBy, () -> {
            synchronized (built) {
                if (built[0] == null) {
                    built[0] = ClasspathGrammar.of(List.of(System.getProperty("java.class.path", "")
                            .split(File.pathSeparator)), loader);
                }
                return built[0];
            }
        }));
    }

    @Override
    public <T> Optional<T> read(Class<?> caller, ManagedValue<T> value) {
        Optional<Path> file = holder(caller, value).file();
        if (file.isEmpty() || !Files.isRegularFile(file.get())) return Optional.empty();
        try {
            return valueIn(Files.readString(file.get()), value);
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    @Override
    public <T> Optional<String> update(Class<?> caller, ManagedValue<T> value, UnaryOperator<T> change) {
        Holder found = holder(caller, value);
        if (found.why().isPresent()) return found.why();
        Path file = found.file().orElseThrow();
        if (!Files.isRegularFile(file)) {
            return Optional.of(file.getFileName() + " is not on this PC yet: BotMaker makes it when it starts a run.");
        }
        synchronized (WRITING) {
            try (FileChannel channel = FileChannel.open(found.folder().orElseThrow().resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                String source = Files.readString(file);
                Optional<T> read = valueIn(source, value);
                if (read.isEmpty() && MarkedReturns.returned(JavaLevel.running(), source, value.holder(),
                        markerName(value), constant(value)).isPresent()) {
                    // Written by a newer version of the plugin, or by hand: never replaced by this run's own start.
                    return Optional.of(file.getFileName() + "'s " + constant(value)
                            + " does not read with this bot's version of its plugin, so it is left as it is.");
                }
                T kept = read.orElseGet(() -> initial(value));
                Optional<JavaValue> spelled = grammar.get().spellData(value.type(), change.apply(kept));
                if (spelled.isEmpty()) return Optional.of("No plugin can write a " + value.type().getSimpleName() + ".");
                Optional<String> written = MarkedReturns.withReturned(JavaLevel.running(), source, value.holder(),
                        markerName(value), constant(value), spelled.get());
                if (written.isEmpty()) {
                    // Most often the host loaded a build of the plugin from before the value, so it did not add it.
                    return Optional.of(file.getFileName() + " has no " + constant(value) + " that returns one value."
                            + " Unless it was edited by hand, BotMaker had loaded another build of the plugin when it"
                            + " started this run: reload the project's plugins, or restart BotMaker's core, and run"
                            + " again.");
                }
                if (!written.get().equals(source)) writeAtomically(file, written.get());
                return Optional.empty();
            } catch (IOException | OverlappingFileLockException e) {
                return Optional.of("Could not write " + file + ": " + e.getMessage());
            } catch (RuntimeException unwritten) {
                return Optional.of("The value could not be written: " + unwritten.getMessage());
            }
        }
    }

    /** {@code value} as {@code source} returns it; empty when it returns none that reads. */
    private <T> Optional<T> valueIn(String source, ManagedValue<T> value) {
        return MarkedReturns.returned(JavaLevel.running(), source, value.holder(), markerName(value), constant(value))
                .flatMap(expression -> grammar.get().read(value.type(), expression)).map(value::cast);
    }

    /** What {@code value} starts as, or null when it declares nothing. */
    private static <T> T initial(ManagedValue<T> value) {
        return value instanceof ManagedMethod<T> method ? method.initial() : null;
    }

    /** The holder class {@code value} lives in, for {@code caller}; else why not. */
    private Holder holder(Class<?> caller, ManagedValue<?> value) {
        if (!value.onHost()) return Holder.why(value.id() + " is the bot's value, not this PC's.");
        Class<?> marker;
        try {
            marker = Class.forName(value.marker(), false, caller.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return Holder.why("The mark " + value.marker() + " cannot be loaded: " + e.getMessage());
        }
        Optional<Path> owner = location(marker);
        if (owner.isEmpty() || !owner.equals(location(caller))) {
            return Holder.why(caller.getName() + " is not " + value.marker() + "'s plugin: a run writes only its own"
                    + " plugin's values of this PC.");
        }
        Optional<PluginCoordinate> plugin = owners.apply(owner.get());
        if (plugin.isEmpty()) return Holder.why(owner.get() + " declares no plugin.");
        String group = plugin.get().groupId().equals(OLDER_GROUP) ? BOTMAKER_GROUP : plugin.get().groupId();
        String g = PluginState.part(group);
        String a = PluginState.part(plugin.get().artifactId());
        Path folder = root.resolve("plugins").resolve(g).resolve(a);
        return Holder.of(folder, folder.resolve(SOURCES).resolve(g).resolve(a).resolve(value.holder() + ".java"));
    }

    private static Optional<Path> location(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        URL url = source == null ? null : source.getLocation();
        if (url == null) return Optional.empty();
        try {
            return Optional.of(Path.of(url.toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<PluginCoordinate> declaredBy(Path location) {
        List<PluginPoms.PluginPom> poms = PluginPoms.find(location).poms();
        return poms.size() == 1 ? Optional.of(poms.getFirst().coordinate()) : Optional.empty();
    }

    /** The marker's simple name, {@code SdkHostValue}. */
    private static String markerName(ManagedValue<?> value) {
        String binary = value.marker();
        return binary.substring(Math.max(binary.lastIndexOf('.'), binary.lastIndexOf('$')) + 1);
    }

    /** The id's constant, {@code DONE}. */
    private static String constant(ManagedValue<?> value) {
        return value.id().substring(value.id().lastIndexOf('.') + 1);
    }

    private static void writeAtomically(Path file, String written) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, written);
            // Windows refuses the move while another process reads the file (Studio's copy does, unlocked): try again.
            for (int tries = MOVE_TRIES; ; tries--) {
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (AccessDeniedException held) {
                    if (tries <= 1) throw held;
                    LockSupport.parkNanos(MOVE_WAIT.toNanos());
                }
            }
        } catch (IOException failed) {
            Files.deleteIfExists(temporary);
            throw failed;
        }
    }

    /** A plugin's folder and the holder class a value lives in, or why there is none. */
    private record Holder(Optional<Path> folder, Optional<Path> file, Optional<String> why) {
        static Holder of(Path folder, Path file) {
            return new Holder(Optional.of(folder), Optional.of(file), Optional.empty());
        }

        static Holder why(String reason) {
            return new Holder(Optional.empty(), Optional.empty(), Optional.of(reason));
        }
    }
}
