package com.botmaker.plugin.host;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What a plugin's compiled classes link in the contract and the host's contract does not have.
 *
 * <h2>Why a link check and not a version</h2>
 *
 * <p>A plugin built against a newer contract than the host's fails the first time it calls the member the
 * host lacks: a {@link NoSuchMethodError} three screens into a session, from a button that looked fine. Nothing
 * earlier can compare versions, because there are none to compare — every module's pom says the cosmetic
 * {@code 0.0.0-SNAPSHOT} and JitPack's tag is not in the jar. What is in the jar is every reference it makes,
 * in each class's constant pool, with its owner, name and descriptor. So the host reads those and looks each
 * one that reaches the contract up in its own. A plugin whose every link resolves loads; one that names
 * something the host lacks is refused at load, with the member named.
 *
 * <p>It is sharper than a version, too. A plugin built against a newer contract that uses nothing new loads
 * fine, which a version comparison would have refused.
 *
 * <p>A version is still read, for one thing only: <em>which way</em> a refused plugin is out of step
 * ({@link Direction}). The plugin's own version is not in its jar, but its flattened pom names the contract tag
 * it pinned, and that is what tells <i>update the plugin</i> from <i>update Studio</i>. It never admits or
 * refuses anything.
 *
 * <h2>A link reaches the contract two ways</h2>
 *
 * <p>Directly, when its owner is a contract class ({@code ToolbarItem.id(…)}). And through the plugin's own
 * class: javac writes an inherited call as a reference to the class it is made on, so {@code newer()} inside
 * a plugin implementing {@code StudioPlugin} is {@code p/Newer.newer()}. So a reference owned by one of the
 * entry's classes is followed up that class's supertypes, through the entry's classes, the contract's and the
 * JDK's. A supertype from anywhere else (the toolkit, a library) cannot be read from here, and a reference
 * that reaches one is not judged: the check refuses only what it can prove is missing.
 *
 * <h2>The contract is read as class files, never reflected</h2>
 *
 * <p>Reflecting on a contract class links every type its members name, and {@code SlotEditor}'s name
 * {@code javafx.scene.Node} — which a headless host (the CLI, the registry's CI) does not have. So a contract
 * class is read the way a plugin's is: its bytes, off the host's loader. Only a JDK class ({@code Object},
 * {@code Enum}, {@code Record}, where a contract type's inherited members live) is reflected.
 *
 * <p>A contract member counts only when it is {@code public} or {@code protected}. A plugin never shares a
 * package with the contract, so a package-private member — a record's constructor made so when steps replaced
 * it — is as missing to it as a deleted one, and fails the same way at run time.
 *
 * <p>The constant-pool reader follows the contract's {@code catalog.SourceOrder}; that one is package-private
 * and keeps only names, so this is a copy that keeps the references too, rather than a widened contract.
 */
public final class ContractLinks {

    /** The contract's package in class-file spelling. */
    static final String CONTRACT = "com/botmaker/plugin/api/";

    /** The services file whose presence makes a classpath entry a plugin. */
    static final String SERVICES = "META-INF/services/com.botmaker.plugin.api.StudioPlugin";

    private static final int PUBLIC = 0x0001;
    private static final int PROTECTED = 0x0004;

    private ContractLinks() {
    }

    /**
     * One reference into the contract: a class, or a field or method of one.
     *
     * @param owner      the contract class, in class-file spelling
     *                   ({@code com/botmaker/plugin/api/toolbar/ToolbarItem}); for a member reached through a
     *                   plugin's own class, the first contract type above it
     * @param name       the member's name, {@code <init>} for a constructor, or null for the class itself
     * @param descriptor the member's descriptor, or null for the class itself
     * @param field      whether the member is a field
     */
    public record Link(String owner, String name, String descriptor, boolean field) {

        /** {@code ToolbarSteps.Pressing.badge(…)}, {@code ToolbarItem(…)}, {@code Theme.DARK}, {@code Drawn}. */
        public String describe() {
            String type = owner.substring(owner.lastIndexOf('/') + 1).replace('$', '.');
            if (name == null) return type;
            if (field) return type + "." + name;
            return name.equals("<init>") ? type + "(…)" : type + "." + name + "(…)";
        }
    }

    /**
     * Which side of a mismatch is behind, which is what decides what the user updates.
     *
     * <p>Until 2026-09-29 every mismatch read <i>built for a newer Studio</i>, and the first one a user met was
     * the other way round: an SDK built against contract 0.2 in a Studio on 0.3, naming members 0.3 had
     * deleted. The links alone cannot tell the two apart — a member the host lacks is lacking either way — so
     * the direction comes from the contract tag the plugin's own published pom names ({@link #declaredContract})
     * against the one this host was built with.
     */
    public enum Direction {
        /** The plugin pins an older contract than this host's: update the plugin. */
        OLDER,
        /** The plugin pins a newer contract than this host's: update Studio. */
        NEWER,
        /** Either side's tag is unknown (a local build, a hand-made jar): say what is missing, not whose fault. */
        DIFFERENT
    }

    /** Thrown into a load failure: the plugin links what this host's contract has not got. */
    public static final class ContractMismatch extends RuntimeException {

        private final List<Link> missing;
        private final Direction direction;
        private final String pluginContract;

        ContractMismatch(List<Link> missing, Direction direction, String pluginContract) {
            super(message(missing, direction, pluginContract));
            this.missing = List.copyOf(missing);
            this.direction = direction;
            this.pluginContract = pluginContract;
        }

        /** Every reference that did not resolve, in the order the entry's classes were read. */
        public List<Link> missing() {
            return missing;
        }

        public Direction direction() {
            return direction;
        }

        /** The contract tag the plugin was built against ({@code v0.2.1}), or null when its jar does not say. */
        public String pluginContract() {
            return pluginContract;
        }

        private static String message(List<Link> missing, Direction direction, String pluginContract) {
            // A whole missing class says more than each of its members, and says it once.
            Set<String> gone = new HashSet<>();
            for (Link link : missing) if (link.name() == null) gone.add(link.owner());
            List<String> names = missing.stream()
                    .filter(link -> link.name() == null || !gone.contains(link.owner()))
                    .map(Link::describe).distinct().toList();
            int shown = Math.min(3, names.size());
            String listed = String.join(", ", names.subList(0, shown))
                    + (names.size() > shown ? " and " + (names.size() - shown) + " more" : "");
            String built = pluginContract == null ? "" : " (contract " + pluginContract + ")";
            return switch (direction) {
                case OLDER -> "built for an older Studio" + built + "; update the plugin. It uses " + listed
                        + ", which this Studio no longer has";
                case NEWER -> "built for a newer Studio" + built + "; update Studio. It needs " + listed;
                case DIFFERENT -> "built for a different Studio" + built + ": it uses " + listed
                        + ", which this Studio has not got";
            };
        }
    }

    /**
     * The mismatch {@code missing} amounts to for the plugin at {@code entry}, its direction read off the
     * plugin's pinned contract against this host's.
     */
    public static ContractMismatch mismatch(Path entry, List<Link> missing) {
        return mismatch(entry, missing, hostContract());
    }

    static ContractMismatch mismatch(Path entry, List<Link> missing, String hostContract) {
        String plugin = declaredContract(entry);
        return new ContractMismatch(missing, direction(plugin, hostContract), plugin);
    }

    /**
     * Which side is behind. A host without a tag is a build from {@code main} — a development Studio — and
     * {@code main} is at or past every tag, so a tagged plugin that mismatches it is the older one.
     */
    static Direction direction(String pluginContract, String hostContract) {
        int[] plugin = version(pluginContract);
        if (plugin == null) return Direction.DIFFERENT;
        int[] host = version(hostContract);
        if (host == null) return Direction.OLDER;
        int order = Arrays.compare(plugin, host);
        return order < 0 ? Direction.OLDER : order > 0 ? Direction.NEWER : Direction.DIFFERENT;
    }

    /** {@code v1.2.3} or {@code 1.2.3} as three numbers; null for a snapshot or anything else. */
    private static int[] version(String tag) {
        if (tag == null) return null;
        Matcher m = TAG.matcher(tag.trim());
        if (!m.matches()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    private static final Pattern TAG = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");

    private static final Pattern CONTRACT_PIN = Pattern.compile(
            "<artifactId>botmaker-studio-api</artifactId>\\s*<version>([^<]+)</version>");

    /**
     * The contract tag {@code entry}'s published pom pins, or null. Every released module's pom is flattened, so
     * the jar's {@code META-INF/maven/…/pom.xml} carries the tag the release injected, not the property; a class
     * directory carries no pom and answers null.
     */
    public static String declaredContract(Path entry) {
        if (entry == null || !Files.isRegularFile(entry)) return null;
        try (ZipFile zip = new ZipFile(entry.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry each = entries.nextElement();
                String name = each.getName();
                if (!name.startsWith("META-INF/maven/") || !name.endsWith("/pom.xml")) continue;
                try (InputStream in = zip.getInputStream(each)) {
                    String pin = contractPin(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    if (pin != null) return pin;
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static volatile String hostContract;

    /**
     * The contract tag this host was built with: the pin in this module's own published pom, since a host
     * releases with the contract it pins (host is forced by the contract). Null in a development build.
     */
    static String hostContract() {
        String known = hostContract;
        if (known != null) return known.isEmpty() ? null : known;
        String found = null;
        try (InputStream in = ContractLinks.class.getResourceAsStream(
                "/META-INF/maven/com.github.LiQiyeDev/botmaker-plugin-host/pom.xml")) {
            if (in != null) found = contractPin(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            found = null;
        }
        hostContract = found == null ? "" : found;
        return found;
    }

    private static String contractPin(String pom) {
        Matcher m = CONTRACT_PIN.matcher(pom);
        if (!m.find()) return null;
        String pin = m.group(1).trim();
        return pin.startsWith("${") || pin.endsWith("-SNAPSHOT") ? null : pin;
    }

    /** Whether {@code entry}, a jar or a class directory, declares a {@code StudioPlugin}. */
    public static boolean declaresPlugin(Path entry) {
        if (entry == null) return false;
        if (Files.isDirectory(entry)) return Files.isRegularFile(entry.resolve(SERVICES));
        if (!Files.isRegularFile(entry)) return false;
        try (ZipFile zip = new ZipFile(entry.toFile())) {
            return zip.getEntry(SERVICES) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Every contract reference in {@code entry}'s classes that {@code contract} cannot resolve, empty when
     * all of them do.
     *
     * <p>Total: an unreadable jar or class file contributes nothing, because a plugin whose bytes cannot be
     * read will fail to load for that reason, and says so itself. A class that is itself in the contract's
     * package is skipped — a jar that carries the contract is not linking it.
     *
     * @param entry    a jar, or a directory of classes
     * @param contract the loader the host's own contract classes come from
     */
    public static List<Link> missing(Path entry, ClassLoader contract) {
        Map<String, ClassFile> own = new LinkedHashMap<>();
        try {
            if (Files.isDirectory(entry)) {
                try (Stream<Path> files = Files.walk(entry)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                        try (InputStream in = Files.newInputStream(file)) {
                            keep(ClassFile.read(in), own);
                        }
                    }
                }
            } else if (Files.isRegularFile(entry)) {
                try (ZipFile zip = new ZipFile(entry.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry each = entries.nextElement();
                        String name = each.getName();
                        if (!name.endsWith(".class") || name.startsWith("META-INF/")
                                || name.endsWith("module-info.class")) {
                            continue;
                        }
                        try (InputStream in = zip.getInputStream(each)) {
                            keep(ClassFile.read(in), own);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // What could be read is still judged; the rest is the loader's to report.
        }

        Resolver resolver = new Resolver(own, contract);
        Set<Link> missing = new LinkedHashSet<>();
        for (ClassFile file : own.values()) {
            for (Reference reference : file.references()) {
                Link link = resolver.missing(reference);
                if (link != null) missing.add(link);
            }
        }
        return List.copyOf(missing);
    }

    private static void keep(ClassFile file, Map<String, ClassFile> own) {
        if (file != null && file.name() != null && !file.name().startsWith(CONTRACT)) own.put(file.name(), file);
    }

    // ---- class files --------------------------------------------------------------------------------------

    /** A reference out of a class's constant pool: a class, or a member of one. */
    private record Reference(String owner, String name, String descriptor, boolean field) {
    }

    /**
     * What this check needs of one class file: its name, supertypes and members, and the references its
     * constant pool makes. {@code members} holds {@code name + descriptor}, each with its access flags.
     */
    private record ClassFile(String name, String superName, List<String> interfaces,
                             Map<String, Integer> fields, Map<String, Integer> methods,
                             List<Reference> references) {

        /** Null when the bytes are not a class file this reader understands. */
        static ClassFile read(InputStream raw) {
            try {
                DataInputStream in = new DataInputStream(raw);
                if (in.readInt() != 0xCAFEBABE) return null;
                in.readUnsignedShort();                                   // minor version
                in.readUnsignedShort();                                   // major version
                Pool pool = Pool.read(in);
                in.readUnsignedShort();                                   // access flags
                String name = pool.className(in.readUnsignedShort());
                String superName = pool.className(in.readUnsignedShort());
                int interfaceCount = in.readUnsignedShort();
                List<String> interfaces = new ArrayList<>(interfaceCount);
                for (int i = 0; i < interfaceCount; i++) interfaces.add(pool.className(in.readUnsignedShort()));
                Map<String, Integer> fields = members(in, pool);
                Map<String, Integer> methods = members(in, pool);
                return new ClassFile(name, superName, interfaces, fields, methods, pool.references());
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        private static Map<String, Integer> members(DataInputStream in, Pool pool) throws IOException {
            int count = in.readUnsignedShort();
            Map<String, Integer> members = new HashMap<>();
            for (int i = 0; i < count; i++) {
                int access = in.readUnsignedShort();
                String name = pool.utf8[in.readUnsignedShort()];
                String descriptor = pool.utf8[in.readUnsignedShort()];
                int attributes = in.readUnsignedShort();
                for (int j = 0; j < attributes; j++) {
                    in.readUnsignedShort();
                    in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
                }
                members.put(name + descriptor, access);
            }
            return members;
        }

        /** Whether this class itself declares {@code member}, visibly to another package when asked. */
        boolean declares(String member, boolean field, boolean visibleOnly) {
            Integer access = (field ? fields : methods).get(member);
            return access != null && (!visibleOnly || (access & (PUBLIC | PROTECTED)) != 0);
        }
    }

    /**
     * A class file's constant pool: each entry's tag, its UTF-8 text, and its one or two index operands.
     *
     * <p>An unrecognised tag throws, as in {@code SourceOrder}: a constant kind of unknown width makes every
     * later offset meaningless.
     */
    private record Pool(int[] tag, String[] utf8, int[] a, int[] b) {

        static Pool read(DataInputStream in) throws IOException {
            int count = in.readUnsignedShort();
            int[] tag = new int[count];
            String[] utf8 = new String[count];
            int[] a = new int[count];
            int[] b = new int[count];
            for (int i = 1; i < count; i++) {
                tag[i] = in.readUnsignedByte();
                switch (tag[i]) {
                    case 1 -> utf8[i] = in.readUTF();                                   // Utf8
                    case 7, 8, 16, 19, 20 -> a[i] = in.readUnsignedShort();             // Class, String, …
                    case 9, 10, 11, 12 -> {                                             // refs, NameAndType
                        a[i] = in.readUnsignedShort();
                        b[i] = in.readUnsignedShort();
                    }
                    case 15 -> in.skipNBytes(3);                                        // MethodHandle
                    case 3, 4, 17, 18 -> in.skipNBytes(4);                              // Integer, Float, …
                    case 5, 6 -> {                                                      // Long, Double
                        in.skipNBytes(8);
                        i++;                                    // eight-byte constants take two pool slots
                    }
                    default -> throw new IOException("unknown constant pool tag " + tag[i]);
                }
            }
            return new Pool(tag, utf8, a, b);
        }

        String className(int index) {
            return index > 0 && index < tag.length && tag[index] == 7 ? utf8[a[index]] : null;
        }

        /** Every class, field and method this pool refers to, array classes read as their element. */
        List<Reference> references() {
            List<Reference> references = new ArrayList<>();
            for (int i = 1; i < tag.length; i++) {
                if (tag[i] == 7) {
                    String owner = elementClass(utf8[a[i]]);
                    if (owner != null) references.add(new Reference(owner, null, null, false));
                } else if (tag[i] == 9 || tag[i] == 10 || tag[i] == 11) {
                    String owner = elementClass(className(a[i]));
                    int nameAndType = b[i];
                    if (owner != null) {
                        references.add(new Reference(owner, utf8[a[nameAndType]], utf8[b[nameAndType]], tag[i] == 9));
                    }
                }
            }
            return references;
        }

        /** The class an array class names, or the class itself; null for an array of primitives. */
        private static String elementClass(String name) {
            if (name == null || !name.startsWith("[")) return name;
            String element = name.substring(name.lastIndexOf('[') + 1);
            return element.startsWith("L") && element.endsWith(";")
                    ? element.substring(1, element.length() - 1) : null;
        }
    }

    // ---- resolving --------------------------------------------------------------------------------------

    /** Whether a member was found: {@code UNKNOWN} when the search reached a class it cannot read. */
    private enum Found { YES, NO, UNKNOWN }

    /** Resolves references against the entry's own classes, the host's contract and the JDK. */
    private static final class Resolver {

        private final Map<String, ClassFile> own;
        private final ClassLoader contract;
        private final Map<String, ClassFile> contractFiles = new HashMap<>();
        private final Map<String, Set<String>> platformMembers = new HashMap<>();

        Resolver(Map<String, ClassFile> own, ClassLoader contract) {
            this.own = own;
            this.contract = contract;
        }

        /** The link {@code reference} makes into the contract when it does not resolve, else null. */
        Link missing(Reference reference) {
            String owner = reference.owner();
            if (reference.name() == null) {
                return owner.startsWith(CONTRACT) && contractFile(owner) == null
                        ? new Link(owner, null, null, false) : null;
            }
            if (!owner.startsWith(CONTRACT) && !own.containsKey(owner)) return null;
            String member = reference.name() + reference.descriptor();
            if (reference.name().startsWith("<")) {
                // A constructor or initialiser is the class's own and is never inherited.
                if (!owner.startsWith(CONTRACT)) return null;
                ClassFile file = contractFile(owner);
                return file != null && file.declares(member, false, true)
                        ? null : new Link(owner, reference.name(), reference.descriptor(), false);
            }
            List<String> contractTypes = new ArrayList<>();
            Found found = find(owner, member, reference.field(), new HashSet<>(), contractTypes);
            return found == Found.NO && !contractTypes.isEmpty()
                    ? new Link(contractTypes.getFirst(), reference.name(), reference.descriptor(), reference.field())
                    : null;
        }

        /** Whether {@code owner} or a supertype declares {@code member}, as the JVM's resolution would. */
        private Found find(String owner, String member, boolean field, Set<String> seen, List<String> contractTypes) {
            if (owner == null || !seen.add(owner)) return Found.NO;
            ClassFile file;
            if (owner.startsWith(CONTRACT)) {
                contractTypes.add(owner);
                file = contractFile(owner);
                if (file == null) return Found.NO;
                if (file.declares(member, field, true)) return Found.YES;
            } else if (own.containsKey(owner)) {
                file = own.get(owner);
                if (file.declares(member, field, false)) return Found.YES;
            } else if (owner.startsWith("java/") || owner.startsWith("javax/")) {
                Set<String> members = platformMembers(owner, field);
                return members == null ? Found.UNKNOWN : members.contains(member) ? Found.YES : Found.NO;
            } else {
                return Found.UNKNOWN;                               // the toolkit, a library: not readable here
            }
            Found result = Found.NO;
            List<String> supertypes = new ArrayList<>(file.interfaces());
            supertypes.add(file.superName());
            for (String supertype : supertypes) {
                Found above = find(supertype, member, field, seen, contractTypes);
                if (above == Found.YES) return Found.YES;
                if (above == Found.UNKNOWN) result = Found.UNKNOWN;
            }
            return result;
        }

        private ClassFile contractFile(String owner) {
            if (contractFiles.containsKey(owner)) return contractFiles.get(owner);
            ClassFile file = null;
            try (InputStream in = contract.getResourceAsStream(owner + ".class")) {
                if (in != null) file = ClassFile.read(in);
            } catch (IOException e) {
                file = null;
            }
            contractFiles.put(owner, file);
            return file;
        }

        /**
         * The visible members of a JDK class and everything above it, by reflection — safe there, since a JDK
         * class names nothing a headless host lacks. Null when the class cannot be loaded.
         */
        private Set<String> platformMembers(String owner, boolean field) {
            String key = (field ? "F:" : "M:") + owner;
            if (platformMembers.containsKey(key)) return platformMembers.get(key);
            Set<String> members = new HashSet<>();
            try {
                List<Class<?>> types = new ArrayList<>();
                for (Class<?> type = Class.forName(owner.replace('/', '.'), false, contract); type != null;
                     type = type.getSuperclass()) {
                    types.add(type);
                    types.addAll(List.of(type.getInterfaces()));
                }
                for (Class<?> type : types) {
                    if (field) {
                        for (Field each : type.getDeclaredFields()) {
                            if (visible(each.getModifiers())) {
                                members.add(each.getName() + each.getType().descriptorString());
                            }
                        }
                    } else {
                        for (Method each : type.getDeclaredMethods()) {
                            if (visible(each.getModifiers())) {
                                members.add(each.getName() + MethodType.methodType(each.getReturnType(),
                                        each.getParameterTypes()).toMethodDescriptorString());
                            }
                        }
                    }
                }
            } catch (ClassNotFoundException | LinkageError e) {
                members = null;
            }
            platformMembers.put(key, members);
            return members;
        }

        private static boolean visible(int modifiers) {
            return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
        }
    }
}
