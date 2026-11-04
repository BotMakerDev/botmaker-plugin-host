package com.botmaker.plugin.host.grammar;

import com.botmaker.plugin.api.params.Param;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps a bot's values classes fitting their schemas, so a values class never stops a build (2026-11-04, umbrella
 * spec {@code 2026-11-04-private-values-design.md} § Never broken by values).
 *
 * <p>A schema is a public top-level interface marked {@link Param}; its values class is {@code <Schema>Values} in a
 * {@code values} package, implementing it. A values class keeps only the methods its schema declares with the same
 * return type: a method the schema lacks or retyped is dropped, with the imports nothing uses afterwards, and a
 * class whose schema is gone is deleted. Every host runs it before a compile (Studio) and before a write (the bot).
 */
public final class ValuesPruner {

    /** The package segment a values class sits in. */
    public static final String PACKAGE = "values";

    /** The suffix of a values class's name: {@code RunChoices} → {@code RunChoicesValues}. */
    public static final String SUFFIX = "Values";

    private ValuesPruner() {}

    /**
     * One values class this pass changed.
     *
     * @param file    the file
     * @param source  its pruned text, or empty when it was deleted
     * @param dropped the methods dropped from it, or the class itself when deleted
     */
    public record Pruned(Path file, Optional<String> source, List<String> dropped) {
        public Pruned {
            dropped = List.copyOf(dropped);
        }

        /** One line saying what changed, for a console. */
        public String sentence() {
            String name = file.getFileName().toString();
            return source.isEmpty() ? name + " was deleted: its schema is gone."
                    : name + ": dropped " + String.join(", ", dropped) + ", which its schema no longer declares.";
        }
    }

    /** Whether {@code file} may be a values class: a {@code <Schema>Values.java} in a {@code values} folder. */
    public static boolean isValuesFile(Path file) {
        Path parent = file.getParent();
        return file.getFileName().toString().endsWith(SUFFIX + ".java") && parent != null
                && parent.getFileName() != null && parent.getFileName().toString().equals(PACKAGE);
    }

    /**
     * {@code values} pruned against {@code schema}: the text unchanged when it fits, empty when the class must be
     * deleted ({@code schema} null or no {@code @Param} interface).
     */
    public static Optional<String> prune(String schema, String values) {
        Map<String, String> declared = schema == null ? null : schemaMethods(SourceParser.parse(schema));
        CompilationUnit unit = SourceParser.parse(values);
        if (declared == null || implementedSchema(unit) == null) return Optional.empty();
        return Optional.of(pruned(declared, values, unit).source());
    }

    /**
     * Every values class under {@code sourceRoot} pruned, files rewritten or deleted; what changed. Each file is copied
     * into {@code backups} first (overwriting an older copy), so no value is lost to a prune nobody asked for.
     */
    public static List<Pruned> onDisk(Path sourceRoot, Path backups) throws IOException {
        if (!Files.isDirectory(sourceRoot)) return List.of();
        Map<Path, String> sources = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            for (Path file : walk.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".java")).toList()) {
                sources.put(file, Files.readString(file));
            }
        }
        List<Pruned> out = over(sources);
        for (Pruned each : out) {
            backUp(each.file(), backups);
            if (each.source().isPresent()) Files.writeString(each.file(), each.source().get());
            else Files.deleteIfExists(each.file());
        }
        return out;
    }

    /** Copies {@code file} into {@code backups} under its own name, before a host changes or removes it. */
    public static void backUp(Path file, Path backups) throws IOException {
        if (!Files.isRegularFile(file)) return;
        Files.createDirectories(backups);
        Files.copy(file, backups.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Every values class among {@code sources} that pruning changes, with what it becomes. Pure.
     *
     * <p>Nothing is decided while any source does not parse: a schema mid-edit is no reason to drop a value. A values
     * class whose schema is not among the sources but is imported from a package the bot does not declare (a jar's) is
     * left alone, as is a file in a {@code values} folder that is no {@code <Schema>Values} class.
     */
    public static List<Pruned> over(Map<Path, String> sources) {
        Map<Path, CompilationUnit> units = new LinkedHashMap<>();
        for (Map.Entry<Path, String> each : sources.entrySet()) {
            CompilationUnit unit = SourceParser.parse(each.getValue());
            if (SourceParser.hasSyntaxErrors(unit)) return List.of();
            units.put(each.getKey(), unit);
        }
        Map<String, Map<String, String>> schemas = new LinkedHashMap<>();
        Set<String> packages = new HashSet<>();
        units.forEach((file, unit) -> {
            String pkg = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName();
            packages.add(pkg);
            if (isValuesFile(file)) return;
            schema(unit).ifPresent(type -> schemas.put(qualified(pkg, type.getName().getIdentifier()),
                    schemaMethods(unit)));
        });
        List<Pruned> out = new ArrayList<>();
        units.forEach((file, unit) -> {
            if (!isValuesFile(file)) return;
            String implemented = implementedSchema(unit);
            if (implemented == null) return;
            String schema = schemaOf(unit, implemented, schemas.keySet());
            if (schema == null) {
                if (importedFromElsewhere(unit, implemented, packages)) return;
                out.add(new Pruned(file, Optional.empty(), List.of(file.getFileName().toString())));
                return;
            }
            Kept kept = pruned(schemas.get(schema), sources.get(file), unit);
            if (!kept.dropped().isEmpty()) out.add(new Pruned(file, Optional.of(kept.source()), kept.dropped()));
        });
        return List.copyOf(out);
    }

    private record Kept(String source, List<String> dropped) {}

    /** The names {@code java.lang.Object} declares with no parameter: a values class may override them freely. */
    private static final Set<String> OBJECT_METHODS = Set.of("toString", "hashCode", "clone", "finalize", "getClass");

    /**
     * {@code values} against a schema's methods (name → return type): a method of the schema's name and another type
     * is dropped, and an {@code @Override} method the schema lacks. Helpers, parameters' methods, statics and
     * {@code Object}'s methods stay.
     */
    private static Kept pruned(Map<String, String> declared, String values, CompilationUnit unit) {
        TypeDeclaration type = valuesType(unit).orElseThrow();
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        List<String> dropped = new ArrayList<>();
        Set<MethodDeclaration> removed = new HashSet<>();
        for (MethodDeclaration method : type.getMethods()) {
            String name = method.getName().getIdentifier();
            if (method.isConstructor() || !method.parameters().isEmpty() || method.getReturnType2() == null
                    || Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())
                    || OBJECT_METHODS.contains(name)) continue;
            String wanted = declared.get(name);
            boolean stale = wanted == null ? overrides(method) : !shape(method.getReturnType2()).equals(wanted);
            if (!stale) continue;
            rewrite.remove(method, null);
            removed.add(method);
            dropped.add(name + "()");
        }
        if (dropped.isEmpty()) return new Kept(values, List.of());
        Set<String> used = namesUsed(unit, removed);
        for (Object each : unit.imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            if (imported.isStatic() || imported.isOnDemand()) continue;
            String simple = imported.getName().getFullyQualifiedName();
            simple = simple.substring(simple.lastIndexOf('.') + 1);
            if (!used.contains(simple)) rewrite.remove(imported, null);
        }
        Document document = new Document(values);
        try {
            rewrite.rewriteAST(document, null).apply(document);
        } catch (BadLocationException e) {
            return new Kept(values, List.of());
        }
        return new Kept(document.get(), dropped);
    }

    /** Whether {@code method} says it overrides something: a schema method the schema no longer has, here. */
    private static boolean overrides(MethodDeclaration method) {
        for (Object each : method.modifiers()) {
            if (each instanceof Annotation annotation
                    && last(annotation.getTypeName().getFullyQualifiedName()).equals("Override")) return true;
        }
        return false;
    }

    /** The public top-level {@code @Param} interface of {@code unit}, if it declares one. */
    private static Optional<TypeDeclaration> schema(CompilationUnit unit) {
        for (Object each : unit.types()) {
            if (each instanceof TypeDeclaration type && type.isInterface()
                    && Modifier.isPublic(type.getModifiers()) && marked(type)) return Optional.of(type);
        }
        return Optional.empty();
    }

    private static boolean marked(AbstractTypeDeclaration type) {
        for (Object each : type.modifiers()) {
            if (each instanceof IExtendedModifier modifier && modifier.isAnnotation()) {
                String name = ((Annotation) modifier).getTypeName().getFullyQualifiedName();
                if (name.equals(Param.class.getSimpleName()) || name.equals(Param.class.getName())) return true;
            }
        }
        return false;
    }

    /** Each no-parameter instance method of the unit's schema, by name, with its return type's shape; null when none. */
    private static Map<String, String> schemaMethods(CompilationUnit unit) {
        TypeDeclaration type = schema(unit).orElse(null);
        if (type == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (MethodDeclaration method : type.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) && !Modifier.isPrivate(method.getModifiers())
                    && method.parameters().isEmpty() && method.getReturnType2() != null) {
                out.put(method.getName().getIdentifier(), shape(method.getReturnType2()));
            }
        }
        return out;
    }

    /** The top-level class of a values unit: {@code <Schema>Values}, implementing something. */
    private static Optional<TypeDeclaration> valuesType(CompilationUnit unit) {
        for (Object each : unit.types()) {
            if (each instanceof TypeDeclaration type && !type.isInterface()
                    && type.getName().getIdentifier().endsWith(SUFFIX)) return Optional.of(type);
        }
        return Optional.empty();
    }

    /**
     * The qualified name of the schema a values class implements, among {@code schemas}: the one its import names,
     * else the one of that simple name in the bot (a schema moved to another package); null when there is none.
     */
    private static String schemaOf(CompilationUnit unit, String simple, Set<String> schemas) {
        for (Object each : unit.imports()) {
            String imported = ((ImportDeclaration) each).getName().getFullyQualifiedName();
            if (schemas.contains(imported)) return imported;
        }
        List<String> named = schemas.stream().filter(q -> q.equals(simple) || q.endsWith("." + simple))
                .collect(Collectors.toList());
        return named.size() == 1 ? named.getFirst() : null;
    }

    /** The simple name of the schema a {@code <Schema>Values} class implements, or null when it is no such class. */
    private static String implementedSchema(CompilationUnit unit) {
        TypeDeclaration type = valuesType(unit).orElse(null);
        if (type == null) return null;
        String name = type.getName().getIdentifier();
        String simple = name.substring(0, name.length() - SUFFIX.length());
        return ((List<?>) type.superInterfaceTypes()).stream().anyMatch(t -> shape((Type) t).equals(simple))
                ? simple : null;
    }

    /** Whether the values class imports its schema from a package none of the bot's sources is in: a jar's. */
    private static boolean importedFromElsewhere(CompilationUnit unit, String simple, Set<String> packages) {
        for (Object each : unit.imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            if (imported.isStatic() || imported.isOnDemand()) continue;
            String name = imported.getName().getFullyQualifiedName();
            if (last(name).equals(simple)) return !packages.contains(name.substring(0, name.lastIndexOf('.')));
        }
        return false;
    }

    /** Every simple name the unit's types use outside its imports and the removed methods. */
    private static Set<String> namesUsed(CompilationUnit unit, Set<MethodDeclaration> removed) {
        Set<String> out = new HashSet<>();
        for (Object each : unit.types()) {
            ((AbstractTypeDeclaration) each).accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration method) {
                    return !removed.contains(method);
                }

                @Override
                public boolean visit(SimpleName name) {
                    out.add(name.getIdentifier());
                    return false;
                }
            });
        }
        return out;
    }

    /** A type as its simple names spell it, so {@code java.time.Duration} and {@code Duration} are one shape. */
    static String shape(Type type) {
        return switch (type) {
            case SimpleType simple -> last(simple.getName().getFullyQualifiedName());
            case QualifiedType qualified -> qualified.getName().getIdentifier();
            case NameQualifiedType named -> named.getName().getIdentifier();
            case ParameterizedType generic -> shape(generic.getType()) + "<" + ((List<?>) generic.typeArguments())
                    .stream().map(t -> shape((Type) t)).collect(Collectors.joining(",")) + ">";
            case ArrayType array -> shape(array.getElementType()) + "[]".repeat(array.getDimensions());
            default -> type.toString().replace(" ", "");
        };
    }

    private static String last(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String qualified(String pkg, String name) {
        return pkg.isEmpty() ? name : pkg + "." + name;
    }
}
