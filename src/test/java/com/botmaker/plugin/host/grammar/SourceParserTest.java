package com.botmaker.plugin.host.grammar;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Text to tree has one entry (2026-11-04): a fragment comes back only when it is well-formed, whole. */
class SourceParserTest {

    @Test
    void anExpressionIsWholeOrNothing() {
        assertInstanceOf(MethodInvocation.class, SourceParser.expression("  Duration.ofSeconds(5) "));
        assertNull(SourceParser.expression("not java"), "a made-up name is no expression");
        assertNull(SourceParser.expression("1 + 2 junk"), "trailing text is refused");
        assertNull(SourceParser.expression("   "));
        assertNull(SourceParser.expression(null));
    }

    @Test
    void anExpressionMovesIntoTheAstItIsFor() {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        Expression moved = SourceParser.expression(ast, "switch (x) { case 1 -> \"a\"; default -> \"b\"; }");
        assertNotNull(moved, "the newest Java is read");
        assertSame(ast, moved.getAST());
    }

    @Test
    void typesAndStatementsParse() {
        assertEquals("Map<String,int[]>", SourceParser.type("Map<String, int[]>").toString());
        assertNull(SourceParser.type("List<"));
        assertEquals(2, SourceParser.statements("int a = 1; a++;").statements().size());
        assertNull(SourceParser.statements("int a = ;"));
        assertInstanceOf(CastExpression.class, SourceParser.expression("(int) x"));
    }
}
