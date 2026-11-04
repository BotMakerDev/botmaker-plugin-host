package com.botmaker.plugin.host.grammar;

import com.botmaker.plugin.api.params.Param;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTMatcher;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.MalformedTreeException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Keeps a bot's private parameters fitting the author's starting copy, so a parameter never stops a build
 * (2026-11-04, umbrella spec {@code 2026-11-04-params-private-design.md} § Never broken by parameters).
 *
 * <p>A parameter group is a Java file under the bot's {@code params} package, each parameter one no-argument instance
 * method whose body is this computer's value. {@code starting-params/} mirrors it file for file with the author's
 * starting values. Fitting makes each private file the starting file with this computer's bodies: a parameter the
 * starting copy lacks is dropped, one whose type changed is reset to its starting value, one the starting copy adds is
 * added with its starting value, one a rename rule of the starting copy names ({@link #renameTarget}) takes the value
 * of its old name at the same type, and everything but the values (annotations, helpers) is the starting copy's: a
 * value is its body and the {@code @Param.Kept} a run wrote beside it. A file
 * whose starting copy is gone is deleted; a starting file with no private copy is copied. Studio runs it on open and
 * before each compile.
 */
public final class ParamsFitter {

    /** The package segment, and the folder, the private parameter files sit in. */
    public static final String PARAMS = "params";

    /** The bot-root folder holding the author's starting copy, committed. */
    public static final String STARTING = "starting-params";

    /** A backup folder's name: one pass's time, to the millisecond, sorting as it reads. */
    private static final DateTimeFormatter PASS_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private ParamsFitter() {}

    /**
     * One private parameter file this pass changed.
     *
     * @param file    the file, relative to the {@code params} folder
     * @param source  its fitted text, or empty when it was deleted
     * @param changes what changed, one short phrase each ({@code "dropped tries()"})
     */
    public record Fitted(Path file, Optional<String> source, List<String> changes) {
        public Fitted {
            changes = List.copyOf(changes);
        }

        /** One line saying what changed, for a console. */
        public String sentence() {
            return source.isEmpty() ? file + " was deleted: its starting copy is gone."
                    : file + ": " + String.join(", ", changes) + ", to fit its starting copy.";
        }
    }

    /**
     * Every file under {@code paramsRoot} fitted to its copy under {@code startingRoot}, files written, created or
     * deleted; what changed. Each file changed or deleted is first copied into a folder of {@code backups} named by
     * this pass's time, at its relative path, so no value is lost to a fit nobody asked for, however many follow.
     * With no Java file under {@code startingRoot} nothing is decided: a bot without a starting copy, or a checkout
     * that left it empty, loses no parameter.
     */
    public static List<Fitted> onDisk(Path paramsRoot, Path startingRoot, Path backups) throws IOException {
        Map<Path, String> starting = sources(startingRoot);
        if (starting.isEmpty()) return List.of();
        List<Fitted> out = over(sources(paramsRoot), starting);
        Path pass = backups.resolve(PASS_TIME.format(LocalDateTime.now()));
        for (Fitted each : out) {
            Path file = paramsRoot.resolve(each.file());
            backUp(file, pass.resolve(each.file()));
            if (each.source().isPresent()) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, each.source().get());
            } else {
                Files.deleteIfExists(file);
            }
        }
        return out;
    }

    /**
     * Every private file that fitting changes, with what it becomes; both maps keyed by the path relative to their
     * folder. Pure.
     *
     * <p>Nothing is decided while any source of either side does not parse: a file mid-edit is no reason to drop a
     * value.
     */
    public static List<Fitted> over(Map<Path, String> params, Map<Path, String> starting) {
        for (Map<Path, String> side : List.of(params, starting)) {
            for (String source : side.values()) {
                if (SourceParser.hasSyntaxErrors(SourceParser.parse(source))) return List.of();
            }
        }
        Set<Path> files = new TreeSet<>(Comparator.comparing(Path::toString));
        files.addAll(params.keySet());
        files.addAll(starting.keySet());
        List<Fitted> out = new ArrayList<>();
        for (Path file : files) {
            String mine = params.get(file);
            String start = starting.get(file);
            if (start == null) {
                // A file with no @Param class is no group (a bot's Parameters.java of @Param fields): left alone.
                if (isGroup(SourceParser.parse(mine))) out.add(new Fitted(file, Optional.empty(), List.of("deleted")));
            } else if (mine == null) {
                out.add(new Fitted(file, Optional.of(start), List.of("added")));
            } else {
                Fit fit = fitted(start, mine);
                if (!fit.changes().isEmpty()) out.add(new Fitted(file, Optional.of(fit.source()), fit.changes()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@code starting} with the value of its parameter {@code name} taken from {@code params}: the body, the imports
     * that body needs and the time a run wrote it ({@code @Param.Kept}), or no time when {@code params} has none.
     * Unchanged when either side lacks the parameter, their types differ, or a source does not parse.
     */
    public static String withValue(String starting, String params, String name) {
        return taken(starting, params, name, true);
    }

    /**
     * The same without the time, which never reaches a starting copy: what <i>Make this the starting value</i> writes.
     */
    public static String withStartingValue(String starting, String params, String name) {
        return taken(starting, params, name, false);
    }

    private static String taken(String starting, String params, String name, boolean withTime) {
        CompilationUnit startUnit = SourceParser.parse(starting);
        CompilationUnit mineUnit = SourceParser.parse(params);
        if (SourceParser.hasSyntaxErrors(startUnit) || SourceParser.hasSyntaxErrors(mineUnit)) return starting;
        MethodDeclaration start = parameters(startUnit).get(name);
        MethodDeclaration mine = parameters(mineUnit).get(name);
        if (start == null || mine == null || !sameType(mine.getReturnType2(), start.getReturnType2())) {
            return starting;
        }
        Block body = mine.getBody();
        if (start.getBody().subtreeMatch(new ASTMatcher(), body) && (!withTime || sameTime(start, mine))) return starting;
        return withValues(starting, startUnit, Map.of(start, mine), withTime,
                missingImports(startUnit, mineUnit, namesIn(body)));
    }

    /** The {@code @Param.Kept} on {@code method}, or null. */
    static Annotation keptOn(MethodDeclaration method) {
        return BotAnnotation.PARAM_KEPT.on(method);
    }

    private static boolean sameTime(MethodDeclaration one, MethodDeclaration other) {
        Annotation mine = keptOn(one);
        Annotation theirs = keptOn(other);
        return mine == null ? theirs == null : mine.subtreeMatch(new ASTMatcher(), theirs);
    }

    /** Whether {@code unit} declares a top-level class marked {@code @Param}: a parameter group. */
    public static boolean isGroup(CompilationUnit unit) {
        for (Object each : unit.types()) {
            if (each instanceof TypeDeclaration type && isGroupType(type)) return true;
        }
        return false;
    }

    /**
     * Each parameter of the starting copy that a rename rule in its group says was called something else, by its new
     * name, to the old name {@code mine} still holds: a rule at the old type, whose old name the starting copy no longer
     * has and whose new one {@code mine} does not have yet. The first rule in file order wins.
     */
    private static Map<String, String> renamed(CompilationUnit startUnit, Map<String, MethodDeclaration> startMethods,
                                               Map<String, MethodDeclaration> mineMethods) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();
        for (Object each : startUnit.types()) {
            if (!(each instanceof TypeDeclaration type) || type.isInterface()) continue;
            for (MethodDeclaration rule : type.getMethods()) {
                Optional<String> to = renameTarget(rule);
                if (to.isEmpty()) continue;
                String from = rule.getName().getIdentifier();
                MethodDeclaration mine = mineMethods.get(from);
                MethodDeclaration start = startMethods.get(to.get());
                if (mine == null || start == null || startMethods.containsKey(from)
                        || mineMethods.containsKey(to.get()) || out.containsKey(to.get()) || !taken.add(from)) {
                    continue;
                }
                Type oldType = rule.getReturnType2();
                if (sameType(oldType, mine.getReturnType2()) && sameType(oldType, start.getReturnType2())) {
                    out.put(to.get(), from);
                }
            }
            break;
        }
        return out;
    }

    /**
     * The parameter a rename rule says {@code rule}'s name became (2026-11-04): {@code rule} is a static method of a
     * {@code @Param} group marked {@code @Migrate(Group.class)}, taking only its {@code @Migrate.Self Group} receiver and
     * returning that receiver's no-argument call of the new name, at the old type:
     *
     * <pre>{@code
     * @Migrate(RunChoices.class)
     * static int tries(@Migrate.Self RunChoices self) { return self.attempts(); }
     * }</pre>
     *
     * A committed rule in the group's starting copy is how a renamed parameter keeps this computer's value on every
     * copy of the bot. Empty for any other method.
     */
    public static Optional<String> renameTarget(MethodDeclaration rule) {
        if (!(rule.getParent() instanceof TypeDeclaration group) || !isGroupType(group)
                || !Modifier.isStatic(rule.getModifiers()) || rule.parameters().size() != 1 || rule.getBody() == null
                || rule.getBody().statements().size() != 1) {
            return Optional.empty();
        }
        Annotation mark = BotAnnotation.MIGRATE.on(rule);
        if (!(mark instanceof SingleMemberAnnotation single) || !(single.getValue() instanceof TypeLiteral owner)
                || !names(owner.getType(), group)) {
            return Optional.empty();
        }
        SingleVariableDeclaration self = (SingleVariableDeclaration) rule.parameters().getFirst();
        if (BotAnnotation.MIGRATE_SELF.on(self.modifiers()) == null || !names(self.getType(), group)
                || self.getExtraDimensions() != 0 || self.isVarargs()) {
            return Optional.empty();
        }
        if (rule.getBody().statements().getFirst() instanceof ReturnStatement returned
                && returned.getExpression() instanceof MethodInvocation call && call.arguments().isEmpty()
                && call.typeArguments().isEmpty() && call.getExpression() instanceof SimpleName receiver
                && receiver.getIdentifier().equals(self.getName().getIdentifier())) {
            return Optional.of(call.getName().getIdentifier());
        }
        return Optional.empty();
    }

    /** Whether {@code written} names the class {@code group}, as the imports of its file resolve it. */
    private static boolean names(Type written, TypeDeclaration group) {
        return written instanceof SimpleType simple
                && SourceNames.refersTo(simple.getName(), SourceNames.canonicalOf(group));
    }

    /** Whether {@code type} is a parameter group: a class marked with the contract's {@code @Param}. */
    private static boolean isGroupType(TypeDeclaration type) {
        return !type.isInterface() && BotAnnotation.PARAM.on(type) != null;
    }

    /**
     * Whether {@code method} is one parameter of its group: a no-argument, non-void instance method with a body. The
     * one predicate Studio's Parameters window and the fitter read a group with.
     */
    public static boolean isParameter(MethodDeclaration method) {
        int modifiers = method.getModifiers();
        Type returned = method.getReturnType2();
        return !method.isConstructor() && method.parameters().isEmpty() && method.getBody() != null && returned != null
                && !(returned instanceof PrimitiveType primitive && primitive.getPrimitiveTypeCode() == PrimitiveType.VOID)
                && !Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers);
    }

    /** {@code params} fitted to {@code starting}: the text unchanged when it fits. */
    public static String fit(String starting, String params) {
        return fitted(starting, params).source();
    }

    private record Fit(String source, List<String> changes) {}

    private static Fit fitted(String starting, String params) {
        CompilationUnit startUnit = SourceParser.parse(starting);
        CompilationUnit mineUnit = SourceParser.parse(params);
        Map<String, MethodDeclaration> startMethods = parameters(startUnit);
        Map<String, MethodDeclaration> mineMethods = parameters(mineUnit);
        if (sameFrame(starting, params)) return new Fit(params, List.of());
        List<String> changes = new ArrayList<>();
        Map<MethodDeclaration, MethodDeclaration> kept = new LinkedHashMap<>();
        Set<String> usedByKept = new HashSet<>();
        Set<String> dropped = new HashSet<>(mineMethods.keySet());
        dropped.removeAll(startMethods.keySet());
        Map<String, String> renamed = renamed(startUnit, startMethods, mineMethods);
        for (Map.Entry<String, MethodDeclaration> each : startMethods.entrySet()) {
            String name = each.getKey();
            MethodDeclaration start = each.getValue();
            String from = renamed.getOrDefault(name, name);
            MethodDeclaration mine = mineMethods.get(from);
            if (mine == null) {
                changes.add("added " + name + "()");
            } else if (!sameType(mine.getReturnType2(), start.getReturnType2())
                    || namesIn(mine.getBody()).stream().anyMatch(dropped::contains)) {
                changes.add("reset " + name + "()");
            } else {
                kept.put(start, mine);
                usedByKept.addAll(namesIn(mine.getBody()));
                if (!from.equals(name)) changes.add("renamed " + from + "() to " + name + "()");
            }
        }
        mineMethods.keySet().stream().filter(name -> !startMethods.containsKey(name) && !renamed.containsValue(name))
                .forEach(name -> changes.add("dropped " + name + "()"));
        if (changes.isEmpty()) changes.add("matched its starting copy");
        try {
            return new Fit(withValues(starting, startUnit, kept, true, missingImports(startUnit, mineUnit, usedByKept)),
                    changes);
        } catch (IllegalStateException unwritable) {
            return new Fit(params, List.of("could not be fitted: " + unwritable.getMessage()));
        }
    }

    /**
     * {@code source}, the text of {@code unit}, with each of {@code values}' methods given the body of the method of
     * the other file it maps to, copied as a tree, its time too when {@code withTime}, and {@code imports} added. A
     * body already written alike keeps its text; a copied one is laid out by the rewrite, so a comment inside it does
     * not follow.
     *
     * @throws IllegalStateException when the rewrite cannot be applied: never read as "nothing changed", which would
     *         lose a value in silence
     */
    private static String withValues(String source, CompilationUnit unit, Map<MethodDeclaration, MethodDeclaration> values,
                                     boolean withTime, List<ImportDeclaration> imports) {
        AST ast = unit.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        values.forEach((method, from) -> {
            if (!method.getBody().subtreeMatch(new ASTMatcher(), from.getBody())) {
                rewrite.replace(method.getBody(), ASTNode.copySubtree(ast, from.getBody()), null);
            }
            if (withTime && !sameTime(method, from)) {
                ListRewrite modifiers = rewrite.getListRewrite(method, MethodDeclaration.MODIFIERS2_PROPERTY);
                if (keptOn(method) != null) modifiers.remove(keptOn(method), null);
                if (keptOn(from) != null) modifiers.insertFirst(ASTNode.copySubtree(ast, keptOn(from)), null);
            }
        });
        ListRewrite list = rewrite.getListRewrite(unit, CompilationUnit.IMPORTS_PROPERTY);
        for (ImportDeclaration imported : imports) list.insertLast(ASTNode.copySubtree(ast, imported), null);
        Document document = new Document(source);
        try {
            rewrite.rewriteAST(document, SourceFormatter.rewriteOptions()).apply(document);
        } catch (MalformedTreeException | BadLocationException | IllegalArgumentException unwritable) {
            throw new IllegalStateException("the rewrite could not be applied", unwritable);
        }
        return document.get();
    }

    /**
     * Whether the two sources differ only in their imports and their parameters' values (body and time), compared as
     * trees. A comment outside a doc comment is not in the tree, so it never makes a fit.
     */
    private static boolean sameFrame(String startingSource, String paramsSource) {
        CompilationUnit starting = withoutTimes(startingSource);
        CompilationUnit params = withoutTimes(paramsSource);
        Map<String, MethodDeclaration> startMethods = parameters(starting);
        Map<String, MethodDeclaration> mineMethods = parameters(params);
        Set<Block> values = new HashSet<>();
        startMethods.values().forEach(method -> values.add(method.getBody()));
        mineMethods.values().forEach(method -> values.add(method.getBody()));
        ASTMatcher frames = new ASTMatcher(true) {
            @Override
            public boolean match(Block node, Object other) {
                return values.contains(node) && other instanceof Block body && values.contains(body)
                        || super.match(node, other);
            }
        };
        // The trees match by spelling; a Foo each file imports from another package is still another type.
        return frames.safeSubtreeMatch(starting.getPackage(), params.getPackage())
                && frames.safeSubtreeListMatch(starting.types(), params.types())
                && startMethods.entrySet().stream().allMatch(each -> mineMethods.containsKey(each.getKey())
                        && sameType(each.getValue().getReturnType2(), mineMethods.get(each.getKey()).getReturnType2()));
    }

    /** A tree of {@code source} for comparing only, each {@code @Param.Kept} taken off. */
    private static CompilationUnit withoutTimes(String source) {
        CompilationUnit unit = SourceParser.parse(source);
        parameters(unit).values().forEach(method -> method.modifiers().remove(keptOn(method)));
        return unit;
    }

    /** Each parameter of the unit's group, by name: a non-static, non-private, no-argument method with a body. */
    static Map<String, MethodDeclaration> parameters(CompilationUnit unit) {
        Map<String, MethodDeclaration> out = new LinkedHashMap<>();
        for (Object each : unit.types()) {
            if (!(each instanceof TypeDeclaration type) || type.isInterface()) continue;
            for (MethodDeclaration method : type.getMethods()) {
                if (isParameter(method)) out.putIfAbsent(method.getName().getIdentifier(), method);
            }
            break;
        }
        return out;
    }

    /**
     * The imports of {@code mine} that {@code starting} lacks and a kept body may need: a single-type import whose
     * simple name a kept body uses, and every on-demand one while any body is kept (which names it brings cannot be
     * told from the tree).
     */
    private static List<ImportDeclaration> missingImports(CompilationUnit starting, CompilationUnit mine,
                                                          Set<String> used) {
        List<ImportDeclaration> out = new ArrayList<>();
        for (Object each : mine.imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            String name = imported.getName().getFullyQualifiedName();
            boolean needed = imported.isOnDemand() ? !used.isEmpty()
                    : used.contains(name.substring(name.lastIndexOf('.') + 1));
            if (needed && !imports(starting, imported)) out.add(imported);
        }
        return out;
    }

    private static boolean imports(CompilationUnit unit, ImportDeclaration wanted) {
        for (Object each : unit.imports()) {
            if (wanted.subtreeMatch(new ASTMatcher(), each)) return true;
        }
        return false;
    }

    private static Set<String> namesIn(Block body) {
        Set<String> out = new HashSet<>();
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                out.add(name.getIdentifier());
                return false;
            }
        });
        return out;
    }

    /**
     * Whether two types, each in its own file, are one type: each name resolved through its file's imports, so
     * {@code a.Foo} and {@code b.Foo} differ while {@code java.time.Duration} and an imported {@code Duration} are
     * one. A name its file does not import by name (an on-demand import, {@code java.lang}, its own package) is
     * matched by what it spells, as the end of the other.
     */
    static boolean sameType(Type one, Type other) {
        return switch (one) {
            case ParameterizedType generic when other instanceof ParameterizedType theirs ->
                    sameType(generic.getType(), theirs.getType())
                            && sameTypes(generic.typeArguments(), theirs.typeArguments());
            case ArrayType array when other instanceof ArrayType theirs ->
                    array.getDimensions() == theirs.getDimensions()
                            && sameType(array.getElementType(), theirs.getElementType());
            case PrimitiveType primitive when other instanceof PrimitiveType theirs ->
                    primitive.getPrimitiveTypeCode() == theirs.getPrimitiveTypeCode();
            case null -> other == null;
            default -> {
                if (other == null) yield false;
                Optional<Named> mine = named(one);
                Optional<Named> theirs = named(other);
                yield mine.isPresent() && theirs.isPresent() ? same(one, mine.get(), other, theirs.get())
                        : one.subtreeMatch(new ASTMatcher(), other);
            }
        };
    }

    /**
     * Two class names, each as its own file resolves it. A whole name against a simple one from a file with an
     * on-demand import asks that file, so an imported {@code a.Foo} is not the {@code Foo} of {@code import b.*}.
     * Otherwise the simple name is one its file never says, as in a starting copy that has not taken the value's
     * import yet, and it matches by its last segments.
     */
    private static boolean same(Type one, Named mine, Type other, Named theirs) {
        if (mine.whole() && theirs.whole()) return mine.name().equals(theirs.name());
        if (mine.whole() && other instanceof SimpleType simple && importsOnDemand(other)) {
            return SourceNames.refersTo(simple.getName(), mine.name());
        }
        if (theirs.whole() && one instanceof SimpleType simple && importsOnDemand(one)) {
            return SourceNames.refersTo(simple.getName(), theirs.name());
        }
        return mine.name().equals(theirs.name()) || mine.name().endsWith("." + theirs.name())
                || theirs.name().endsWith("." + mine.name());
    }

    private static boolean importsOnDemand(Type type) {
        return type.getRoot() instanceof CompilationUnit unit && unit.imports().stream()
                .anyMatch(each -> ((ImportDeclaration) each).isOnDemand() && !((ImportDeclaration) each).isStatic());
    }

    private static boolean sameTypes(List<?> one, List<?> other) {
        if (one.size() != other.size()) return false;
        for (int i = 0; i < one.size(); i++) {
            if (!sameType((Type) one.get(i), (Type) other.get(i))) return false;
        }
        return true;
    }

    /** A class's name as written, and whether the file's imports made it whole. */
    private record Named(String name, boolean whole) {}

    /** {@code type}'s class name, its first segment resolved by a single-type import of its file; empty if no class. */
    private static Optional<Named> named(Type type) {
        String written = switch (type) {
            case SimpleType simple -> simple.getName().getFullyQualifiedName();
            case NameQualifiedType qualified -> qualified.getQualifier().getFullyQualifiedName() + "."
                    + qualified.getName().getIdentifier();
            case QualifiedType qualified when qualified.getQualifier() instanceof SimpleType outer ->
                    outer.getName().getFullyQualifiedName() + "." + qualified.getName().getIdentifier();
            default -> null;
        };
        if (written == null) return Optional.empty();
        int dot = written.indexOf('.');
        String first = dot < 0 ? written : written.substring(0, dot);
        if (type.getRoot() instanceof CompilationUnit unit) {
            for (Object each : unit.imports()) {
                ImportDeclaration imported = (ImportDeclaration) each;
                String name = imported.getName().getFullyQualifiedName();
                if (!imported.isOnDemand() && !imported.isStatic() && name.endsWith("." + first)) {
                    return Optional.of(new Named(name + written.substring(first.length()), true));
                }
            }
        }
        // A name written whole starts with its package, in lower case: java.time.Duration.
        return Optional.of(new Named(written, dot > 0 && Character.isLowerCase(first.charAt(0))));
    }

    /** Every Java file under {@code root}, keyed by its path relative to it. */
    private static Map<Path, String> sources(Path root) throws IOException {
        Map<Path, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".java")).toList()) {
                out.put(root.relativize(file), Files.readString(file));
            }
        }
        return out;
    }

    private static void backUp(Path file, Path backup) throws IOException {
        if (!Files.isRegularFile(file)) return;
        Files.createDirectories(backup.getParent());
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
    }
}
