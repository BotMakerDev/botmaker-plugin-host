package com.botmaker.plugin.host.grammar;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.Type;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java text read into a JDT tree at the latest language level: the one place a host makes an {@link ASTParser}
 * (2026-11-04). A file, a run of statements, one expression, one type, or a file against a classpath.
 *
 * <p><b>The compiler options are the entire point of this class.</b> {@code ASTParser} defaults to source
 * level <b>1.3</b> — where {@code enum} is an ordinary identifier, and generics and annotations are syntax
 * errors. It does not fail loudly: JDT recovers, and hands back a tree in which an {@code @Override} method or
 * a nested {@code enum} has quietly become a {@code FieldDeclaration} or vanished. Callers that walk that tree
 * then draw confident conclusions from it — "this file has no such method", "there is no outcome enum here" —
 * and act on them.
 *
 * <p>A fragment (an expression, a type, statements) is answered only when it is well-formed: a parser handed
 * a typo still hands back a tree, one it made up, so a fragment the parser marked {@code MALFORMED} or
 * {@code RECOVERED} anywhere is {@code null}.
 */
public final class SourceParser {

    private SourceParser() {}

    /**
     * The compiler options every parse here sets, read once: {@code JavaCore.getOptions()} rebuilds its map from the
     * preferences on each call, and an assistant's picture cut parsed hundreds of expressions (2026-10-16).
     */
    private static final Map<String, String> OPTIONS = Map.copyOf(latestLevelOptions());

    /**
     * The property set on the root of a fragment's tree: an expression with no file around it. JDT wraps one in a
     * synthetic unit, so the tree alone cannot say its imports are missing rather than empty.
     */
    private static final String DETACHED = "botmaker.detached";

    /** Parses {@code source} as a compilation unit. Never null; unparseable input yields a recovered tree. */
    public static CompilationUnit parse(String source) {
        return (CompilationUnit) parser(ASTParser.K_COMPILATION_UNIT, source).createAST(null);
    }

    /**
     * {@code source} as exactly one well-formed expression — {@code Precision.TIGHT}, {@code 0}, {@code "x"} — or
     * {@code null}. Offsets index {@code source} as given. The tree is {@linkplain #detached detached}.
     */
    public static Expression expression(String source) {
        if (source == null || source.isBlank()) return null;
        ASTNode node = parser(ASTParser.K_EXPRESSION, source).createAST(null);
        if (!(node instanceof Expression parsed) || isDamaged(parsed)) return null;
        if (parsed.getRoot() instanceof CompilationUnit root && hasSyntaxErrors(root)) return null;
        if (parsed.getLength() != source.strip().length()) return null;
        parsed.getRoot().setProperty(DETACHED, Boolean.TRUE);
        return parsed;
    }

    /** {@link #expression} copied into {@code ast}, so it can be placed in a tree of that AST, or {@code null}. */
    public static Expression expression(AST ast, String source) {
        Expression parsed = expression(source);
        return parsed == null ? null : (Expression) ASTNode.copySubtree(ast, parsed);
    }

    /**
     * {@code text} as a type ({@code int}, {@code List<String>}, {@code Point[]}), or {@code null}. Read as the
     * type of a cast, which is the one expression whose grammar holds any type at all.
     */
    public static Type type(String text) {
        if (text == null || text.isBlank() || text.contains("(") || text.contains(")")) return null;
        Expression cast = expression("(" + text.strip() + ") null");
        return cast instanceof CastExpression c ? c.getType() : null;
    }

    /** {@code source} as a block of well-formed statements, or {@code null}. */
    public static Block statements(String source) {
        if (source == null) return null;
        ASTNode node = parser(ASTParser.K_STATEMENTS, source).createAST(null);
        if (!(node instanceof Block block) || isDamaged(block)) return null;
        // A syntax error gives an empty block with no mark at all: text that holds no statement is refused.
        return block.statements().isEmpty() && !source.isBlank() ? null : block;
    }

    /**
     * A parser for files against {@code classpath} and {@code sourceRoot}, with bindings and their recovery: the
     * caller sets the source and unit name, or hands it a batch of paths. No source root is legal: bindings
     * then come from the classpath and the unit itself.
     */
    public static ASTParser bound(List<String> classpath, Path sourceRoot) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setIgnoreMethodBodies(false);
        String[] sources = sourceRoot == null ? new String[0] : new String[] {sourceRoot.toAbsolutePath().toString()};
        String[] encodings = sourceRoot == null ? new String[0] : new String[] {"UTF-8"};
        parser.setEnvironment(classpath.toArray(new String[0]), sources, encodings, true);
        Map<String, String> options = new HashMap<>(OPTIONS);
        options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.latestSupportedJavaVersion());
        // Read by UnusedImports on every canvas edit; stated rather than left to JDT's default.
        options.put(JavaCore.COMPILER_PB_UNUSED_IMPORT, JavaCore.WARNING);
        parser.setCompilerOptions(options);
        return parser;
    }

    private static ASTParser parser(int kind, String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(kind);
        parser.setSource(source.toCharArray());
        parser.setCompilerOptions(new HashMap<>(OPTIONS));
        return parser;
    }

    /** Whether {@code node} came from a fragment parse — an expression with no file, so no imports to read. */
    public static boolean detached(ASTNode node) {
        return node != null && Boolean.TRUE.equals(node.getRoot().getProperty(DETACHED));
    }

    /** Whether the parser marked any node under {@code root} as made up or broken. */
    public static boolean isDamaged(ASTNode root) {
        boolean[] damaged = {false};
        root.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if ((node.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0) damaged[0] = true;
            }
        });
        return damaged[0];
    }

    /**
     * JDT's option map raised to the latest language level — the table above, as a value — with doc comments read.
     *
     * <p>Exposed because the <em>formatter</em> reads the same compliance keys off the same map and would
     * otherwise carry a second, silently divergent copy: a formatter left at 1.3 does not fail, it reformats
     * a {@code switch} rule or a text block into something that no longer parses.
     */
    public static Map<String, String> latestLevelOptions() {
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
        return options;
    }

    /**
     * True when {@code cu} didn't parse cleanly. With bindings unresolved these are syntax errors only, so an
     * unknown type or an unimported class is <em>not</em> reported — which is what makes this usable as a
     * "is this file currently mangled?" check on a user's half-written source.
     */
    public static boolean hasSyntaxErrors(CompilationUnit cu) {
        return firstSyntaxError(cu) != null;
    }

    /**
     * The first error {@link IProblem} in {@code cu}, or {@code null} when it parsed cleanly — the same verdict
     * as {@link #hasSyntaxErrors}, keeping the problem itself so a caller that refuses an edit can say which
     * one broke it. A refusal that only reports "something broke" is not diagnosable from a log.
     */
    public static IProblem firstSyntaxError(CompilationUnit cu) {
        for (IProblem problem : cu.getProblems()) {
            if (problem.isError()) return problem;
        }
        return null;
    }

    /**
     * True when {@code cu} has an error in JDT's <em>syntax</em> category — the one question to ask of a tree
     * parsed <b>with</b> bindings, where {@link #hasSyntaxErrors} would also count a type error such as a
     * static call to an instance method, which leaves a perfectly readable file.
     */
    public static boolean doesNotParse(CompilationUnit cu) {
        for (IProblem problem : cu.getProblems()) {
            if (problem.isError() && (problem.getID() & IProblem.Syntax) != 0) return true;
        }
        return false;
    }
}
