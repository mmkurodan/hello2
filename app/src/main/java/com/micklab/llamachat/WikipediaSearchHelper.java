package com.micklab.llamachat;

import android.text.TextUtils;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;

/**
 * Wikipedia Search API を使った無料Web検索。
 * クエリ言語を検出して一致する言語版を優先し、タイトル直接マッチを先頭に挿入する。
 */
public final class WikipediaSearchHelper {

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private WikipediaSearchHelper() {}

    /**
     * ユーザー発話から検索キーワードを LLM で抽出する（翻訳なし）。
     * 失敗時は原文をそのまま返す。
     */
    public static String extractKeywords(OkHttpClient client, String baseUrl,
                                         String model, String userInput) {
        if (TextUtils.isEmpty(userInput)) return userInput;
        if (client == null || TextUtils.isEmpty(baseUrl)) return userInput;
        try {
            JSONObject body = new JSONObject();
            body.put("model", TextUtils.isEmpty(model) ? "default" : model);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content",
                            "ユーザーのメッセージから検索エンジン向けのキーワードを抽出してください。\n"
                            + "ルール:\n"
                            + "- 固有名詞・専門用語を個別に分解してスペース区切りで並べる\n"
                            + "- 「AとBの違い」→「A B 違い」のように比較対象を個別キーワードに分解する\n"
                            + "- 「〜を教えて」「〜を調べて」「〜を検索して」などの指示語は含めない\n"
                            + "- 翻訳しない（元の言語のまま）\n"
                            + "出力形式（どちらか一方のみ）:\n"
                            + "SEARCH: <キーワード1 キーワード2 ...>\n"
                            + "NONE\n"
                            + "説明・余計な出力は不要。"));
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", userInput));
            body.put("messages", messages);
            body.put("stream", false);
            body.put("options", new JSONObject().put("temperature", 0).put("num_predict", 80));

            Request request = new Request.Builder()
                    .url(baseUrl + "/api/chat")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA))
                    .build();
            try (Response resp = client.newCall(request).execute()) {
                if (!resp.isSuccessful()) return userInput;
                String respBody = resp.body() != null ? resp.body().string() : "";
                JSONObject json = new JSONObject(respBody);
                String content = json.has("message")
                        ? json.getJSONObject("message").optString("content", "").trim() : "";
                content = content.replaceAll("<think>[\\s\\S]*?</think>", "").trim();
                if (content.startsWith("SEARCH:")) {
                    String kw = content.substring("SEARCH:".length()).trim();
                    return kw.isEmpty() ? userInput : kw;
                }
                return userInput;
            }
        } catch (Exception e) {
            return userInput;
        }
    }

    /**
     * クエリ言語を優先しながら日本語・英語 Wikipedia を検索して結果を統合する。
     * タイトルにクエリが含まれる記事は先頭に配置する。
     */
    public static String search(OkHttpClient client, String query) {
        if (client == null || TextUtils.isEmpty(query)) return null;
        try {
            boolean cjk = hasCjkChars(query);
            String primary = cjk ? "ja" : "en";
            String secondary = cjk ? "en" : "ja";

            String primaryPart = searchWikipedia(client, primary, query);
            String secondaryPart = searchWikipedia(client, secondary, query);

            StringBuilder combined = new StringBuilder();
            if (primaryPart != null) combined.append(primaryPart);
            if (secondaryPart != null) {
                if (combined.length() > 0) combined.append("\n");
                combined.append(secondaryPart);
            }

            String result = combined.toString().trim();
            return result.isEmpty() ? null : "SEARCH_RESULTS:\n" + result;
        } catch (Exception e) {
            return null;
        }
    }

    // タイトル直接マッチを先頭に、キーワード検索結果をその後に並べる
    private static String searchWikipedia(OkHttpClient client, String lang, String query) {
        try {
            StringBuilder sb = new StringBuilder();
            LinkedHashSet<String> seen = new LinkedHashSet<>();

            // 1. クエリをタイトルとして直接ルックアップ（タイトル優先）
            // Summary API がリダイレクト先の正規タイトルを返すためそれを重複キーに使う
            JSONObject exactSummary = fetchSummary(client, lang, query);
            boolean titleMatched = exactSummary != null;
            if (exactSummary != null) {
                String canonicalTitle = exactSummary.optString("title", query).trim();
                String extract = exactSummary.optString("extract", "").trim();
                if (extract.length() > 1500) extract = extract.substring(0, 1500);
                seen.add(canonicalTitle);
                appendArticle(sb, lang, canonicalTitle, null, extract);
            }

            // 2. キーワード全文検索（タイトル直接マッチがあればスキップ）
            // タイトルマッチがある場合はそれが最も関連性が高いため、
            // キーワード検索で返る周辺記事（Seiko等）の混入を防ぐ
            if (!titleMatched) {
                String url = "https://" + lang + ".wikipedia.org/w/api.php"
                        + "?action=query&list=search&srsearch="
                        + java.net.URLEncoder.encode(query.trim(), "UTF-8")
                        + "&srlimit=3&srprop=timestamp&utf8=1&format=json";
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "llamachat/1.0 (Android)")
                        .addHeader("Accept", "application/json")
                        .get()
                        .build();

                try (Response resp = client.newCall(request).execute()) {
                    if (resp.isSuccessful()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        JSONObject json = new JSONObject(body);
                        JSONArray hits = json.getJSONObject("query").getJSONArray("search");
                        int added = 0;
                        for (int i = 0; i < hits.length() && added < 2; i++) {
                            JSONObject hit = hits.getJSONObject(i);
                            String title = hit.optString("title", "").trim();
                            if (title.isEmpty() || seen.contains(title)) continue;
                            JSONObject summary = fetchSummary(client, lang, title);
                            if (summary == null) continue;
                            String canonicalTitle = summary.optString("title", title).trim();
                            if (seen.contains(canonicalTitle)) continue;
                            seen.add(canonicalTitle);
                            String ts = hit.optString("timestamp", "");
                            String updated = ts.length() >= 10 ? ts.substring(0, 10) : ts;
                            String extract = summary.optString("extract", "").trim();
                            if (extract.length() > 1500) extract = extract.substring(0, 1500);
                            appendArticle(sb, lang, canonicalTitle, updated, extract);
                            added++;
                        }
                    }
                }
            }

            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    private static void appendArticle(StringBuilder sb, String lang,
                                       String title, String updated, String extract) {
        String articleUrl = "https://" + lang + ".wikipedia.org/wiki/";
        try {
            articleUrl += java.net.URLEncoder.encode(title.replace(" ", "_"), "UTF-8");
        } catch (Exception e) {
            articleUrl += title.replace(" ", "_");
        }
        if (sb.length() > 0) sb.append("\n");
        sb.append("[Wikipedia/").append(lang).append("] ").append(title);
        if (updated != null && !updated.isEmpty()) sb.append(" (").append(updated).append(")");
        sb.append("\n");
        if (extract != null && !extract.isEmpty()) sb.append(extract).append("\n");
        sb.append(articleUrl).append("\n");
    }

    // Wikipedia Summary REST API で記事 JSON を取得する（title・extract を含む）
    private static JSONObject fetchSummary(OkHttpClient client, String lang, String title) {
        try {
            String url = "https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/"
                    + java.net.URLEncoder.encode(title.replace(" ", "_"), "UTF-8");
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "llamachat/1.0 (Android)")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build();
            try (Response resp = client.newCall(request).execute()) {
                if (!resp.isSuccessful()) return null;
                String body = resp.body() != null ? resp.body().string() : "";
                JSONObject json = new JSONObject(body);
                if ("disambiguation".equals(json.optString("type", ""))) return null;
                if (json.optString("extract", "").trim().isEmpty()) return null;
                return json;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // クエリに CJK 文字（日本語・中国語）が含まれるか判定
    private static boolean hasCjkChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(s.charAt(i));
            if (block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }
}
