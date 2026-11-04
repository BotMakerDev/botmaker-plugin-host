package com.botmaker.plugin.host.grammar;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps a bot's private parameters fitting the author's starting copy, so a parameter never stops a build
 * (2026-11-04, umbrella spec {@code 2026-11-04-params-private-design.md} § Never broken by parameters).
 *
 * <p>A parameter group is a Java file under the bot's {@code params} package, each parameter one no-argument instance
 * method whose body is this computer's value. {@code starting-params/} mirrors it file for file with the author's
 * starting values. Fitting makes each private file the starting file with this computer's bodies: a parameter the
 * starting copy lacks is dropped, one whose type changed is reset to its starting value, one the starting copy adds is
 * added with its starting value, and everything but the bodies (annotations, helpers) is the starting copy's. A file
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
     * {@code starting} with the body of its parameter {@code name} taken from {@code params}, and the imports that
     * body needs: what <i>Make this the starting value</i> writes. Unchanged when either side lacks the parameter,
     * their types differ, or a source does not parse.
     */
    public static String withValue(String starting, String params, String name) {
        CompilationUnit startUnit = SourceParser.parse(starting);
        CompilationUnit mineUnit = SourceParser.parse(params);
        if (SourceParser.hasSyntaxErrors(startUnit) || SourceParser.hasSyntaxErrors(mineUnit)) return starting;
        MethodDeclaration start = parameters(startUnit).get(name);
        MethodDeclaration mine = parameters(mineUnit).get(name);
        if (start == null || mine == null
                || !shape(mine.getReturnType2()).equals(shape(start.getReturnType2()))) {
            return starting;
        }
        Block body = mine.getBody();
        List<Splice> splices = new ArrayList<>(List.of(new Splice(start.getBody().getStartPosition(),
                end(start.getBody()), params.substring(body.getStartPosition(), end(body)))));
        String missing = missingImports(startUnit, mineUnit, namesIn(body));
        if (!missing.isEmpty()) splices.add(new Splice(importsAt(startUnit), importsAt(startUnit), missing));
        splices.sort(Comparator.comparingInt(Splice::start).reversed());
        StringBuilder out = new StringBuilder(starting);
        for (Splice each : splices) out.replace(each.start(), each.end(), each.text());
        return out.toString();
    }

    /** Whether {@code unit} declares a top-level class marked {@code @Param}: a parameter group. */
    public static boolean isGroup(CompilationUnit unit) {
        for (Object each : unit.types()) {
            if (!(each instanceof TypeDeclaration type) || type.isInterface()) continue;
            for (Object modifier : type.modifiers()) {
                if (modifier instanceof Annotation annotation) {
                    String name = annotation.getTypeName().getFullyQualifiedName();
                    if (name.equals("Param") || name.endsWith(".Param")) return true;
                }
            }
        }
        return false;
    }

    /** {@code params} fitted to {@code starting}: the text unchanged when it fits. */
    public static String fit(String starting, String params) {
        return fitted(starting, params).source();
    }

    private record Fit(String source, List<String> changes) {}

    /** One splice of the starting text: {@code [start, end)} becomes {@code text}. */
    private record Splice(int start, int end, String text) {}

    private static Fit fitted(String starting, String params) {
        CompilationUnit startUnit = SourceParser.parse(starting);
        CompilationUnit mineUnit = SourceParser.parse(params);
        Map<String, MethodDeclaration> startMethods = parameters(startUnit);
        Map<String, MethodDeclaration> mineMethods = parameters(mineUnit);
        if (frame(starting, startUnit, startMethods).equals(frame(params, mineUnit, mineMethods))) {
            return new Fit(params, List.of());
        }
        List<String> changes = new ArrayList<>();
        List<Splice> splices = new ArrayList<>();
        Set<String> usedByKept = new HashSet<>();
        Set<String> dropped = new HashSet<>(mineMethods.keySet());
        dropped.removeAll(startMethods.keySet());
        startMethods.forEach((name, start) -> {
            MethodDeclaration mine = mineMethods.get(name);
            if (mine == null) {
                changes.add("added " + name + "()");
            } else if (!shape(mine.getReturnType2()).equals(shape(start.getReturnType2()))
                    || namesIn(mine.getBody()).stream().anyMatch(dropped::contains)) {
                changes.add("reset " + name + "()");
            } else {
                Block body = mine.getBody();
                splices.add(new Splice(start.getBody().getStartPosition(), end(start.getBody()),
                        params.substring(body.getStartPosition(), end(body))));
                usedByKept.addAll(namesIn(body));
            }
        });
        mineMethods.keySet().stream().filter(name -> !startMethods.containsKey(name))
                .forEach(name -> changes.add("dropped " + name + "()"));
        if (changes.isEmpty()) changes.add("matched its starting copy");
        String missing = missingImports(startUnit, mineUnit, usedByKept);
        if (!missing.isEmpty()) splices.add(new Splice(importsAt(startUnit), importsAt(startUnit), missing));        splices.sort(Comparator.comparingInt(Splice::start).reversed());
        StringBuilder out = new StringBuilder(starting);
        for (Splice each : splices) out.replace(each.start(), each.end(), each.text());
        return new Fit(out.toString(), changes);
    }

    /**
     * The file's text with its imports left out, each parameter's body emptied and every whitespace removed: two
     * files of one frame differ only in their values.
     */
    private static String frame(String source, CompilationUnit unit, Map<String, MethodDeclaration> parameters) {
        List<Splice> cuts = new ArrayList<>();
        for (Object each : unit.imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            cuts.add(new Splice(imported.getStartPosition(), end(imported), ""));
        }
        for (MethodDeclaration method : parameters.values()) {
            cuts.add(new Splice(method.getBody().getStartPosition(), end(method.getBody()), "{}"));
        }
        cuts.sort(Comparator.comparingInt(Splice::start).reversed());
        StringBuilder out = new StringBuilder(source);
        for (Splice each : cuts) out.replace(each.start(), each.end(), each.text());
        return out.toString().replaceAll("\\s+", "");
    }

    /** Each parameter of the unit's group, by name: a non-static, non-private, no-argument method with a body. */
    private static Map<String, MethodDeclaration> parameters(CompilationUnit unit) {
        Map<String, MethodDeclaration> out = new LinkedHashMap<>();
        for (Object each : unit.types()) {
            if (!(each instanceof TypeDeclaration type) || type.isInterface()) continue;
            for (MethodDeclaration method : type.getMethods()) {
                if (!method.isConstructor() && method.parameters().isEmpty() && method.getReturnType2() != null
                        && !method.getReturnType2().toString().equals("void") && method.getBody() != null && !Modifier.isStatic(method.getModifiers())
                        && !Modifier.isPrivate(method.getModifiers())) {
                    out.putIfAbsent(method.getName().getIdentifier(), method);
                }
            }
            break;
        }
        return out;
    }

    /**
     * The imports of {@code mine} that {@code starting} lacks and a kept body may need, each on a line of its own
     * after a line break: a single-type import whose simple name a kept body uses, and every on-demand one while any
     * body is kept (which names it brings cannot be told from the text).
     */
    private static String missingImports(CompilationUnit starting, CompilationUnit mine, Set<String> used) {
        Set<String> present = new HashSet<>();
        for (Object each : starting.imports()) present.add(each.toString().trim());
        StringBuilder out = new StringBuilder();
        for (Object each : mine.imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            String name = imported.getName().getFullyQualifiedName();
            boolean needed = imported.isOnDemand() ? !used.isEmpty()
                    : used.contains(name.substring(name.lastIndexOf('.') + 1));
            if (!needed || present.contains(imported.toString().trim())) continue;
            out.append('\n').append(imported.toString().trim());
        }
        return out.toString();
    }

    /** Where new import lines go: at the end of the last import, else of the package declaration. */
    private static int importsAt(CompilationUnit unit) {
        List<?> imports = unit.imports();
        if (!imports.isEmpty()) return end((ImportDeclaration) imports.getLast());
        return unit.getPackage() == null ? 0 : end(unit.getPackage());
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

    private static int end(ASTNode node) {
        return node.getStartPosition() + node.getLength();
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
