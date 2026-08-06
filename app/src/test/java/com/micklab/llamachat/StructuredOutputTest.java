package com.micklab.llamachat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link StructuredOutput} の純粋部分（GBNF 生成・Mode 解析）の検証。
 * JSON Schema / apply* は org.json（Android スタブ）を実行時に踏むためここでは対象外。
 */
public class StructuredOutputTest {

    @Test
    public void modeFromString_defaultsAndParsing() {
        assertEquals(StructuredOutput.Mode.OFF, StructuredOutput.Mode.fromString(null));
        assertEquals(StructuredOutput.Mode.OFF, StructuredOutput.Mode.fromString(""));
        assertEquals(StructuredOutput.Mode.OFF, StructuredOutput.Mode.fromString("nonsense"));
        assertEquals(StructuredOutput.Mode.OFF, StructuredOutput.Mode.fromString("OFF"));
        assertEquals(StructuredOutput.Mode.SCHEMA, StructuredOutput.Mode.fromString("schema"));
        assertEquals(StructuredOutput.Mode.SCHEMA, StructuredOutput.Mode.fromString("  SCHEMA "));
        assertEquals(StructuredOutput.Mode.GBNF, StructuredOutput.Mode.fromString("gbnf"));
    }

    @Test
    public void modeOrdinalsMatchSpinnerOrder() {
        // 設定スピナーの並び（0:OFF, 1:SCHEMA, 2:GBNF）と ordinal を一致させる前提。
        assertEquals(0, StructuredOutput.Mode.OFF.ordinal());
        assertEquals(1, StructuredOutput.Mode.SCHEMA.ordinal());
        assertEquals(2, StructuredOutput.Mode.GBNF.ordinal());
    }

    @Test
    public void genericJson_hasStandardRules() {
        String g = StructuredOutput.genericJsonGbnf();
        assertTrue(g.contains("root ::="));
        assertTrue(g.contains("value ::="));
        assertTrue(g.contains("object ::="));
        assertTrue(g.contains("array ::="));
        assertTrue(g.contains("member ::="));
        assertTrue(g.contains("number ::="));
        assertTrue(g.contains("string ::="));
        assertTrue(g.contains("ws ::="));
    }

    @Test
    public void grammars_quotesAreBalanced() {
        // 非エスケープ二重引用符の個数が偶数（文字列リテラルが開閉している）。
        // 文字クラス [^"\\] と ["\\/bfnrt] の 2 個ぶんも偶数寄与なので全体は偶数のはず。
        assertEquals(0, countUnescapedQuotes(StructuredOutput.genericJsonGbnf()) % 2);
    }

    private static int countUnescapedQuotes(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i++; // バックスラッシュは次の 1 文字をエスケープ → スキップ
                continue;
            }
            if (ch == '"') {
                c++;
            }
        }
        return c;
    }
}
