package com.micklab.llamachat;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ユーザ発話から記憶を構造化抽出する（LLM連携）。
 *
 * <p>分類（Memo/ToDo/Plan）・内容・計画日時（年月日時分）・場所を JSON で得て、
 * {@link MemoryRecord} 生成に必要な項目へ正規化する。ネットワーク呼び出しを伴うため
 * 呼び出し側はワーカースレッドで実行すること。失敗時はキーワード除去のみのフォールバックを返す。</p>
 */
public final class MemoryExtractor {

    private static final MediaType JSON_MEDIA =
            MediaType.get("application/json; charset=utf-8");

    /** 抽出結果。TODO の年月日欠落や MEMO のスケジュールは正規化済み。 */
    public static final class Extracted {
        public String category = MemoryRecord.CAT_MEMO;
        public String action = "SAVE";   // "SAVE" or "UPDATE"
        public String targetQuery;       // UPDATE のとき既存レコードを検索するキーワード
        public String content = "";
        public Integer year;
        public Integer month;
        public Integer day;
        public Integer hour;
        public Integer minute;
        public String location;
    }

    private MemoryExtractor() {
    }

    public static Extracted extract(OkHttpClient client, String baseUrl, String model,
                                    StructuredOutput.Mode mode, String appLanguage,
                                    String userMsg) {
        Extracted ex = new Extracted();
        ex.content = fallbackContent(userMsg);
        if (client == null || TextUtils.isEmpty(baseUrl)) {
            return normalize(ex, appLanguage);
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            JSONObject body = new JSONObject();
            body.put("model", TextUtils.isEmpty(model) ? "default" : model);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system")
                    .put("content", buildSystemPrompt(appLanguage, now)));
            messages.put(new JSONObject().put("role", "user")
                    .put("content", userMsg == null ? "" : userMsg));
            body.put("messages", messages);
            body.put("stream", false);
            JSONObject options = new JSONObject();
            options.put("temperature", 0);
            body.put("options", options);
            // 抽出は JSON を要するため、OFF のときも SCHEMA（Ollama の format=json）を用いる。
            StructuredOutput.Mode effective = (mode == null || mode == StructuredOutput.Mode.OFF)
                    ? StructuredOutput.Mode.SCHEMA : mode;
            StructuredOutput.applyGenericJson(body, effective);

            Request request = new Request.Builder()
                    .url(baseUrl + "/api/chat")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) return normalize(ex, appLanguage);
                JSONObject json = new JSONObject(respBody);
                String content = json.has("message")
                        ? json.getJSONObject("message").optString("content", "")
                        : "";
                content = stripThink(content);
                JSONObject parsed = parseFirstJsonObject(content);
                if (parsed != null) applyParsed(ex, parsed);
            }
        } catch (Exception ignored) {
            // フォールバックのまま返す
        }
        return normalize(ex, appLanguage);
    }

    private static String buildSystemPrompt(String lang, LocalDateTime now) {
        boolean ja = "ja".equals(lang);
        String dow = now.getDayOfWeek().getDisplayName(
                TextStyle.SHORT, ja ? Locale.JAPAN : Locale.US);
        String stamp = String.format(Locale.US, "%04d-%02d-%02d (%s) %02d:%02d",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(), dow,
                now.getHour(), now.getMinute());
        String schema = "{\"category\":\"MEMO|TODO|PLAN\",\"action\":\"SAVE|UPDATE\","
                + "\"targetQuery\":string|null,\"content\":\"...\","
                + "\"year\":int|null,\"month\":int|null,\"day\":int|null,"
                + "\"hour\":int|null,\"minute\":int|null,\"location\":string|null}";
        if (ja) {
            return "あなたはユーザの発話から「記憶」を構造化するアシスタントです。\n"
                    + "現在日時: " + stamp + "。相対的な日付（明日・来週・毎朝など）はこの日時を基準に解釈してください。\n"
                    + "次の JSON オブジェクトのみを出力し、前後に文章やコードブロックを付けないでください:\n"
                    + schema + "\n"
                    + "分類の基準:\n"
                    + "- PLAN(予定): 会議・約束・イベントなど。日時や場所があれば入れる。"
                    + "繰り返しは年/月/日を null にする（毎年→year を null、毎月→year と month を null、毎日→year/month/day を null）。"
                    + "終日は hour と minute を null。日時がまったく無ければすべて null。\n"
                    + "- TODO(やること/タスク): 期限のあるやること。year・month・day は必ず埋める"
                    + "（明示が無ければ現在日付）。時刻・場所は任意。\n"
                    + "- MEMO(メモ): 単なる事実やメモ。year〜minute と location はすべて null。\n"
                    + "- action: \"SAVE\"（新規）または \"UPDATE\"（変更）。"
                    + "「〜を〜に変更」「〜の日時を修正」など変更意図が明確なとき UPDATE にする。\n"
                    + "- targetQuery: UPDATE のとき、変更対象を特定するキーワード（例: 「聖歌隊練習を変更」→ \"聖歌隊練習\"）。SAVE のとき null。\n"
                    + "- content には覚える中身だけを簡潔に書き、「覚えておいて」等の指示語は含めない。UPDATE のとき content・日時は変更後の値。\n"
                    + "- 不明な項目は null。月は1〜12、日は1〜31、時は0〜23、分は0〜59。";
        }
        return "You extract a structured memory from the user's utterance.\n"
                + "Current datetime: " + stamp + ". Resolve relative dates (tomorrow, next week, every morning) against it.\n"
                + "Output ONLY the following JSON object, with no surrounding prose or code fences:\n"
                + schema + "\n"
                + "Categories:\n"
                + "- PLAN: meetings/appointments/events. Fill date/time/location when present. "
                + "For recurrence set year/month/day to null (yearly -> year null, monthly -> year & month null, daily -> year/month/day null). "
                + "All-day -> hour and minute null. If no date/time at all, set them all null.\n"
                + "- TODO: a task with a deadline. year, month, day MUST be filled (use the current date if unspecified). time/location optional.\n"
                + "- MEMO: a plain fact/note. Set year..minute and location all null.\n"
                + "- action: \"SAVE\" (new entry) or \"UPDATE\" (modify existing). Use UPDATE when the user clearly wants to change/reschedule something.\n"
                + "- targetQuery: when action=UPDATE, a keyword to find the existing record (e.g. \"choir practice\"). null when SAVE.\n"
                + "- content holds only what to remember; do not include trigger phrases like \"remember\". For UPDATE, content and date/time reflect the new values.\n"
                + "- Unknown fields null. month 1-12, day 1-31, hour 0-23, minute 0-59.";
    }

    private static void applyParsed(Extracted ex, JSONObject o) {
        String cat = o.optString("category", ex.category);
        ex.category = MemoryRecord.normalizeCategory(cat);
        String action = o.optString("action", "SAVE").trim().toUpperCase(Locale.ROOT);
        ex.action = "UPDATE".equals(action) ? "UPDATE" : "SAVE";
        Object tq = o.opt("targetQuery");
        if (tq != null && !JSONObject.NULL.equals(tq)) {
            String s = String.valueOf(tq).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) ex.targetQuery = s;
        }
        String content = o.optString("content", "");
        if (!TextUtils.isEmpty(content)) ex.content = content.trim();
        ex.year = clampInt(o.opt("year"), 1, 9999);
        ex.month = clampInt(o.opt("month"), 1, 12);
        ex.day = clampInt(o.opt("day"), 1, 31);
        ex.hour = clampInt(o.opt("hour"), 0, 23);
        ex.minute = clampInt(o.opt("minute"), 0, 59);
        Object loc = o.opt("location");
        if (loc != null && !JSONObject.NULL.equals(loc)) {
            String s = String.valueOf(loc).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) ex.location = s;
        }
    }

    /** 分類ごとの制約を適用する（TODO は年月日必須、MEMO はスケジュールなし）。 */
    private static Extracted normalize(Extracted ex, String lang) {
        ex.category = MemoryRecord.normalizeCategory(ex.category);
        if (MemoryRecord.CAT_MEMO.equals(ex.category)) {
            ex.year = ex.month = ex.day = ex.hour = ex.minute = null;
            ex.location = null;
        } else if (MemoryRecord.CAT_TODO.equals(ex.category)) {
            LocalDate today = LocalDate.now();
            if (ex.year == null) ex.year = today.getYear();
            if (ex.month == null) ex.month = today.getMonthValue();
            if (ex.day == null) ex.day = today.getDayOfMonth();
        }
        // 時のみ指定（"10時"）は 0 分扱い。分のみは終日に落とす。
        if (ex.hour == null) {
            ex.minute = null;
        } else if (ex.minute == null) {
            ex.minute = 0;
        }
        return ex;
    }

    private static Integer clampInt(Object value, int min, int max) {
        Integer i = toInt(value);
        if (i == null) return null;
        return (i >= min && i <= max) ? i : null;
    }

    private static Integer toInt(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            String s = String.valueOf(value).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripThink(String s) {
        if (s == null) return "";
        return s.replaceAll("<think>[\\s\\S]*?</think>", " ").trim();
    }

    private static JSONObject parseFirstJsonObject(String s) {
        if (TextUtils.isEmpty(s)) return null;
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            return new JSONObject(s.substring(a, b + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /** LLM に頼れないときの content。保存トリガ語のみ除去する。 */
    static String fallbackContent(String userMsg) {
        if (TextUtils.isEmpty(userMsg)) return "";
        String result = userMsg;
        String[] triggers = {
                "覚えておいてください", "覚えておいて", "覚えておく",
                "記録しておいてください", "記録しておいて",
                "メモしておいてください", "メモしておいて",
                "記憶しておいてください", "記憶しておいて",
                "メモして", "記録して", "記憶して"
        };
        for (String trigger : triggers) {
            result = result.replace(trigger, "").trim();
        }
        if (result.endsWith("を") || result.endsWith("が") || result.endsWith("は")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result.trim();
    }
}
