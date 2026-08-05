package com.micklab.llamachat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 構造化出力（文法制約デコード）のサポート。
 *
 * <p>同一スキーマから 2 形式を生成して、バックエンドに応じて使い分ける：</p>
 * <ul>
 *   <li><b>SCHEMA</b>: Ollama の {@code format}（JSON Schema）。Ollama が内部で文法へコンパイルする。
 *       現行の {@code /api/chat}・{@code /api/generate} のまま使える。</li>
 *   <li><b>GBNF</b>: llama.cpp({@code llama-server}) 系が受け付ける生の {@code grammar}（GBNF 文字列）。</li>
 *   <li><b>OFF</b>: 何も付けない（従来どおりプロンプト＋検証/リトライ）。</li>
 * </ul>
 *
 * <p>カレンダー判定の strict JSON はスキーマで厳密に固定し、汎用 JSON モードは任意の JSON 値を許す。</p>
 */
public final class StructuredOutput {

    public enum Mode {
        OFF, SCHEMA, GBNF;

        public static Mode fromString(String s) {
            if (s == null) {
                return OFF;
            }
            try {
                return Mode.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return OFF;
            }
        }
    }

    private StructuredOutput() {
    }

    // ===== リクエストボディへの適用 =====

    /** 任意の JSON 値を強制する汎用制約を付与する（汎用 JSON モード用）。 */
    public static void applyGenericJson(JSONObject body, Mode mode) throws JSONException {
        if (body == null || mode == null) {
            return;
        }
        if (mode == Mode.SCHEMA) {
            body.put("format", "json"); // Ollama のフリーフォーム JSON モード
        } else if (mode == Mode.GBNF) {
            body.put("grammar", genericJsonGbnf());
        }
    }

    // ===== GBNF =====

    /** 任意の JSON 値を許す標準 GBNF（json.gbnf 相当）。 */
    public static String genericJsonGbnf() {
        StringBuilder g = new StringBuilder();
        g.append("root ::= ws value\n");
        g.append("value ::= object | array | string | number | ")
                .append(lit("true")).append(" | ").append(lit("false")).append(" | ").append(lit("null")).append("\n");
        g.append("object ::= ").append(lit("{")).append(" ws ( member ( ").append(lit(",")).append(" ws member )* )? ")
                .append(lit("}")).append(" ws\n");
        g.append("member ::= string ws ").append(lit(":")).append(" ws value\n");
        g.append("array ::= ").append(lit("[")).append(" ws ( value ( ").append(lit(",")).append(" ws value )* )? ")
                .append(lit("]")).append(" ws\n");
        g.append("number ::= ").append(lit("-")).append("? ( ").append(lit("0")).append(" | [1-9] [0-9]* ) ( ")
                .append(lit(".")).append(" [0-9]+ )? ( [eE] [-+]? [0-9]+ )? ws\n");
        appendCommonRules(g);
        return g.toString();
    }

    /** string / strchar / hex / ws の共通規則。 */
    private static void appendCommonRules(StringBuilder g) {
        g.append("string ::= ").append(lit("\"")).append(" strchar* ").append(lit("\"")).append(" ws\n");
        g.append("strchar ::= [^\"\\\\] | ").append(lit("\\"))
                .append(" ( [\"\\\\/bfnrt] | ").append(lit("u")).append(" hex hex hex hex )\n");
        g.append("hex ::= [0-9a-fA-F]\n");
        g.append("ws ::= [ \\t\\n]*\n");
    }

    /** GBNF 文字列リテラル：内容を引用符で囲み、\\ と " をエスケープする。 */
    private static String lit(String content) {
        return "\"" + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** JSON のキー＋コロン（例：{@code "action":}）の GBNF 文字列リテラル。 */
    private static String key(String name) {
        return lit("\"" + name + "\":");
    }
}
