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
     * Web 検索 API（Brave 等）向け: 複数キーワードをスペース区切りで抽出する。
     * 失敗時は原文をそのまま返す。
     */
    public static String extractKeywordsMulti(OkHttpClient client, String baseUrl,
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
     * Wikipedia 向け: 最も重要な 1 語（または自然な複合名詞）を抽出する。
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
                            "ユーザーのメッセージから、Wikipediaで検索するのに最適な1つのキーワードを抽出してください。\n"
                            + "ルール:\n"
                            + "- 最も重要な固有名詞・専門用語を1語（または自然な複合名詞）だけ選ぶ\n"
                            + "- 比較や対比があれば（例: 「AとBの違い」）、主題として最も重要な1語を選ぶ\n"
                            + "- 「〜を教えて」「〜を調べて」「〜を検索して」などの指示語は含めない\n"
                            + "- 翻訳しない（元の言語のまま）\n"
                            + "出力形式（どちらか一方のみ）:\n"
                            + "SEARCH: <キーワード1語>\n"
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
     *
     * @param articleLimit  キーワード検索で追加取得する最大記事数（言語ごと）
     * @param extractLength  記事 extract の最大文字数
     */
    public static String search(OkHttpClient client, String query, int articleLimit, int extractLength) {
        if (client == null || TextUtils.isEmpty(query)) return null;
        if (articleLimit < 1) articleLimit = 1;
        if (extractLength < 100) extractLength = 100;
        try {
            String lang = hasCjkChars(query) ? "ja" : "en";
            String result = searchWikipedia(client, lang, query, articleLimit, extractLength);
            if (result == null || result.isEmpty()) return null;
            return "SEARCH_RESULTS:\n" + result;
        } catch (Exception e) {
            return null;
        }
    }

    // タイトル直接マッチを先頭に、キーワード検索結果をその後に並べる
    private static String searchWikipedia(OkHttpClient client, String lang, String query,
                                           int articleLimit, int extractLength) {
        try {
            StringBuilder sb = new StringBuilder();
            LinkedHashSet<String> seen = new LinkedHashSet<>();

            // 1. クエリをタイトルとして直接ルックアップ（タイトル優先）
            JSONObject exactSummary = fetchSummary(client, lang, query);
            boolean titleMatched = exactSummary != null;
            if (exactSummary != null) {
                String canonicalTitle = exactSummary.optString("title", query).trim();
                String extract = fetchFullExtract(client, lang, canonicalTitle, extractLength);
                if (extract == null) extract = exactSummary.optString("extract", "").trim();
                if (extract.length() > extractLength) extract = extract.substring(0, extractLength);
                seen.add(canonicalTitle);
                appendArticleSections(sb, lang, canonicalTitle, null, extract);
            }

            // 2. キーワード全文検索（タイトル直接マッチがあればスキップ）
            if (!titleMatched) {
                int srlimit = Math.min(articleLimit + 1, 10);
                String url = "https://" + lang + ".wikipedia.org/w/api.php"
                        + "?action=query&list=search&srsearch="
                        + java.net.URLEncoder.encode(query.trim(), "UTF-8")
                        + "&srlimit=" + srlimit + "&srprop=timestamp&utf8=1&format=json";
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
                        for (int i = 0; i < hits.length() && added < articleLimit; i++) {
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
                            String extract = fetchFullExtract(client, lang, canonicalTitle, extractLength);
                            if (extract == null) extract = summary.optString("extract", "").trim();
                            if (extract.length() > extractLength) extract = extract.substring(0, extractLength);
                            appendArticleSections(sb, lang, canonicalTitle, updated, extract);
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

    /**
     * 記事を Wikipedia のセクション見出し（== ... ==）単位に分割し、
     * それぞれ [Wikipedia/lang] Title > SectionName 形式で sb に追記する。
     * セクションのない記事（またはイントロのみ）は 1 チャンクとして追記する。
     * チャンクが RAG の入力単位となるため、embedding 類似度で関連セクションを選別できる。
     */
    private static void appendArticleSections(StringBuilder sb, String lang,
                                               String title, String updated, String extract) {
        if (extract == null || extract.isEmpty()) return;

        String articleUrl = buildArticleUrl(lang, title);
        String titleTag = "[Wikipedia/" + lang + "] " + title;
        if (updated != null && !updated.isEmpty()) titleTag += " (" + updated + ")";

        StringBuilder currentContent = new StringBuilder();
        String currentHeader = null;

        for (String line : extract.split("\n", -1)) {
            String trimmed = line.trim();
            // セクション見出し判定: == ... == 形式（先頭と末尾が = で囲まれ内部に非 = 文字を含む）
            if (trimmed.length() >= 3 && trimmed.charAt(0) == '='
                    && trimmed.charAt(trimmed.length() - 1) == '=') {
                int depth = 0;
                while (depth < trimmed.length() && trimmed.charAt(depth) == '=') depth++;
                String inner = trimmed.substring(depth, trimmed.length() - depth).trim();
                if (!inner.isEmpty() && !inner.contains("=")) {
                    flushSection(sb, titleTag, currentHeader, currentContent.toString(), articleUrl);
                    currentContent = new StringBuilder();
                    currentHeader = inner;
                    continue;
                }
            }
            currentContent.append(line).append("\n");
        }
        flushSection(sb, titleTag, currentHeader, currentContent.toString(), articleUrl);
    }

    private static void flushSection(StringBuilder sb, String titleTag, String header,
                                      String content, String articleUrl) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) return;
        if (sb.length() > 0) sb.append("\n");
        sb.append(titleTag);
        if (header != null) sb.append(" > ").append(header);
        sb.append("\n").append(trimmed).append("\n").append(articleUrl).append("\n");
    }

    private static String buildArticleUrl(String lang, String title) {
        try {
            return "https://" + lang + ".wikipedia.org/wiki/"
                    + java.net.URLEncoder.encode(title.replace(" ", "_"), "UTF-8");
        } catch (Exception e) {
            return "https://" + lang + ".wikipedia.org/wiki/" + title.replace(" ", "_");
        }
    }

    // MediaWiki Action API で記事全文をプレーンテキストで取得し、ローカルで文字数を制限する。
    // exchars はサーバ側で ~1200 文字に制限されるため使用せず、全文取得後にローカルで truncate する。
    // セクション見出しは "== 見出し ==" 形式で残り、LLM にとって有用な構造情報となる。
    private static String fetchFullExtract(OkHttpClient client, String lang,
                                            String title, int extractLength) {
        try {
            String url = "https://" + lang + ".wikipedia.org/w/api.php"
                    + "?action=query&prop=extracts&titles="
                    + java.net.URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
                    + "&explaintext=1&redirects=1&utf8=1&format=json";
            okhttp3.OkHttpClient fetchClient = client.newBuilder()
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "llamachat/1.0 (Android)")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build();
            try (Response resp = fetchClient.newCall(request).execute()) {
                if (!resp.isSuccessful()) return null;
                String body = resp.body() != null ? resp.body().string() : "";
                JSONObject json = new JSONObject(body);
                JSONObject pages = json.getJSONObject("query").getJSONObject("pages");
                java.util.Iterator<String> keys = pages.keys();
                if (!keys.hasNext()) return null;
                String pageId = keys.next();
                if ("-1".equals(pageId)) return null;
                String extract = pages.getJSONObject(pageId).optString("extract", "").trim();
                if (extract.isEmpty()) return null;
                return extract.length() > extractLength ? extract.substring(0, extractLength) : extract;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Wikipedia Summary REST API で記事 JSON を取得する（タイトル確認・曖昧さ回避・リダイレクト解決用）
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
