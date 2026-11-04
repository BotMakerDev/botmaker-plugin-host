package com.botmaker.plugin.host.grammar;

import com.botmaker.plugin.api.params.Param;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One parameter's value written into its group's source, for every host that writes one: Studio's Parameters window
 * and a running bot (2026-11-04).
 */
public final class ParamValues {

    private ParamValues() {}

    /**
     * {@code source} with the parameter {@code method} of the class {@code group} returning {@code value}, and the
     * imports {@code value} names; empty when the source does not parse, or there is no such class or method, or the
     * method's body is not one {@code return}.
     */
    public static Optional<String> withReturned(String source, String group, String method, JavaValue value) {
        return written(source, group, method, value, null);
    }

    /** The same for a value a run wrote at {@code at}: the method carries that time, {@code @Param.Kept}. */
    public static Optional<String> withReturned(String source, String group, String method, JavaValue value,
                                                Instant at) {
        return written(source, group, method, value, at);
    }

    private static Optional<String> written(String source, String group, String method, JavaValue value, Instant at) {
        CompilationUnit unit = SourceParser.parse(source);
        if (value == null || SourceParser.hasSyntaxErrors(unit)) return Optional.empty();
        Optional<MethodDeclaration> declared = declared(unit, group, method);
        Optional<Expression> returned = declared.flatMap(ParamValues::returned);
        if (returned.isEmpty()) return Optional.empty();
        AST ast = unit.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        rewrite.replace(returned.get(), value.copyInto(ast), null);
        if (at != null) writeTime(declared.get(), at, rewrite);
        addImports(unit, rewrite, value.imports());
        Document document = new Document(source);
        try {
            TextEdit edits = rewrite.rewriteAST(document, SourceFormatter.rewriteOptions());
            edits.apply(document);
        } catch (MalformedTreeException | BadLocationException | IllegalArgumentException unwritable) {
            return Optional.empty();
        }
        return Optional.of(document.get());
    }

    /**
     * {@code held}, a host's copy of a group file, with the value of each during-a-run parameter the run changed
     * taken from {@code disk}, where the run wrote it: one whose value differs from {@code before}, the file as the
     * run started. Every other value stays as held: it is the person's. Unchanged when a source does not parse.
     */
    public static String withRunsValues(String held, String before, String disk) {
        CompilationUnit unit = SourceParser.parse(held);
        if (SourceParser.hasSyntaxErrors(unit)) return held;
        String out = held;
        for (MethodDeclaration method : ParamsFitter.parameters(unit).values()) {
            String name = method.getName().getIdentifier();
            if (changesDuringRun(method) && !ParamsFitter.withValue(before, disk, name).equals(before)) {
                out = ParamsFitter.withValue(out, disk, name);
            }
        }
        return out;
    }

    /**
     * The time on {@code group}'s parameter {@code method}, {@code @Param.Kept}: when a run last wrote its value.
     * Empty when it has none, or the source or the time does not read.
     */
    public static Optional<Instant> keptAt(String source, String group, String method) {
        CompilationUnit unit = SourceParser.parse(source);
        if (SourceParser.hasSyntaxErrors(unit)) return Optional.empty();
        return declared(unit, group, method).map(ParamsFitter::keptOn)
                .filter(NormalAnnotation.class::isInstance).map(NormalAnnotation.class::cast)
                .flatMap(ParamValues::timeOf);
    }

    private static Optional<Instant> timeOf(NormalAnnotation kept) {
        for (Object each : kept.values()) {
            if (each instanceof MemberValuePair pair && pair.getName().getIdentifier().equals("at")
                    && pair.getValue() instanceof StringLiteral time) {
                try {
                    return Optional.of(Instant.parse(time.getLiteralValue()));
                } catch (DateTimeParseException garbled) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /** Whether {@code method} carries {@code @Param.Shown(changes = …DURING_RUN)}. */
    private static boolean changesDuringRun(MethodDeclaration method) {
        for (Object modifier : method.modifiers()) {
            if (!(modifier instanceof Annotation annotation) || !annotation.isNormalAnnotation()) continue;
            String name = annotation.getTypeName().getFullyQualifiedName();
            if (!name.equals("Shown") && !name.endsWith(".Shown")) continue;
            for (Object each : ((NormalAnnotation) annotation).values()) {
                MemberValuePair pair = (MemberValuePair) each;
                String constant = pair.getValue().toString();
                if (pair.getName().getIdentifier().equals("changes")
                        && constant.substring(constant.lastIndexOf('.') + 1).equals(Param.Changes.DURING_RUN.name())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code group}'s parameter {@code method}. */
    private static Optional<MethodDeclaration> declared(CompilationUnit unit, String group, String method) {
        for (Object each : unit.types()) {
            if (!(each instanceof TypeDeclaration type) || !type.getName().getIdentifier().equals(group)) continue;
            for (MethodDeclaration declared : type.getMethods()) {
                if (declared.getName().getIdentifier().equals(method) && declared.parameters().isEmpty()) {
                    return Optional.of(declared);
                }
            }
        }
        return Optional.empty();
    }

    /** What the one {@code return} of {@code method} returns. */
    private static Optional<Expression> returned(MethodDeclaration method) {
        Block body = method.getBody();
        if (body == null || body.statements().size() != 1) return Optional.empty();
        return body.statements().getFirst() instanceof ReturnStatement ret
                ? Optional.ofNullable(ret.getExpression()) : Optional.empty();
    }

    /** {@code @Param.Kept(at = …)} on {@code method}, replacing the one there, spelling {@code Param} as its class does. */
    private static void writeTime(MethodDeclaration method, Instant at, ASTRewrite rewrite) {
        AST ast = method.getAST();
        StringLiteral time = ast.newStringLiteral();
        time.setLiteralValue(at.toString());
        MemberValuePair pair = ast.newMemberValuePair();
        pair.setName(ast.newSimpleName("at"));
        pair.setValue(time);
        NormalAnnotation kept = ast.newNormalAnnotation();
        kept.setTypeName(ast.newName(paramSpelling((TypeDeclaration) method.getParent()) + ".Kept"));
        kept.values().add(pair);
        ListRewrite modifiers = rewrite.getListRewrite(method, MethodDeclaration.MODIFIERS2_PROPERTY);
        Annotation there = ParamsFitter.keptOn(method);
        if (there != null) modifiers.replace(there, kept, null);
        else modifiers.insertFirst(kept, null);
    }

    private static String paramSpelling(TypeDeclaration group) {
        for (Object modifier : group.modifiers()) {
            if (modifier instanceof Annotation annotation) {
                String name = annotation.getTypeName().getFullyQualifiedName();
                if (name.equals("Param") || name.endsWith(".Param")) return name;
            }
        }
        return "Param";
    }

    private static void addImports(CompilationUnit unit, ASTRewrite rewrite, List<String> imports) {
        Set<String> present = new HashSet<>();
        for (Object each : unit.imports()) {
            ImportDeclaration declared = (ImportDeclaration) each;
            if (!declared.isOnDemand() && !declared.isStatic()) present.add(declared.getName().getFullyQualifiedName());
        }
        String ownPackage = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName();
        AST ast = unit.getAST();
        ListRewrite list = rewrite.getListRewrite(unit, CompilationUnit.IMPORTS_PROPERTY);
        for (String name : imports) {
            int dot = name.lastIndexOf('.');
            String packageOf = dot < 0 ? "" : name.substring(0, dot);
            if (dot < 0 || packageOf.equals("java.lang") || packageOf.equals(ownPackage) || !present.add(name)) continue;
            ImportDeclaration added = ast.newImportDeclaration();
            added.setName(ast.newName(name));
            list.insertLast(added, null);
        }
    }
}
