package com.micklab.llamachat;

import android.text.TextUtils;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * DuckDuckGo Instant Answer + Wikipedia Search を組み合わせた無料Web検索。
 *
 * <p>DDG は英語クエリを前提とするため、{@link #extractEnglishKeywords} で
 * ユーザー発話から英語キーワードを1回の LLM 呼び出しで直接抽出してから
 * {@link #search} を呼ぶ。抽出失敗時は原文をそのまま使う。</p>
 *
 * <p>{@link #search} は DDG Instant Answer と Wikipedia Search API の両方を呼び、
 * 結果を統合して返す。Wikipedia は比較的最近の更新も含むため補完的に機能する。</p>
 */
public final class DuckDuckGoSearchHelper {

    private static final String DDG_API_URL = "https://api.duckduckgo.com/";
    private static final String WIKI_API_URL = "https://en.wikipedia.org/w/api.php";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private DuckDuckGoSearchHelper() {}

    /**
     * ユーザー発話から英語の検索キーワードを1回の LLM 呼び出しで直接抽出する。
     * 失敗時は原文をそのまま返す。
     */
    public static String extractEnglishKeywords(OkHttpClient client, String baseUrl,
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
                            "Extract web search keywords from the user's message and output them in English.\n"
                            + "Output format — exactly one of:\n"
                            + "SEARCH: <English keywords>\n"
                            + "NONE\n"
                            + "No explanation. No other output."));
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", userInput));
            body.put("messages", messages);
            body.put("stream", false);
            JSONObject options = new JSONObject();
            options.put("temperature", 0);
            options.put("num_predict", 60);
            body.put("options", options);

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
     * DDG Instant Answer + Wikipedia Search を呼び、結果を統合して返す。
     * 英語クエリを渡すこと。両方とも結果がなければ null を返す。
     */
    public static String search(OkHttpClient client, String englishQuery) {
        if (client == null || TextUtils.isEmpty(englishQuery)) return null;
        try {
            String ddgPart = fetchDdg(client, englishQuery);
            String wikiPart = searchWikipedia(client, englishQuery);

            StringBuilder combined = new StringBuilder();
            if (ddgPart != null) combined.append(ddgPart);
            if (wikiPart != null) {
                if (combined.length() > 0) combined.append("\n");
                combined.append(wikiPart);
            }

            String result = combined.toString().trim();
            return result.isEmpty() ? null : "SEARCH_RESULTS:\n" + result;
        } catch (Exception e) {
            return null;
        }
    }

    // DDG Instant Answer API
    private static String fetchDdg(OkHttpClient client, String englishQuery) {
        try {
            String url = DDG_API_URL + "?q="
                    + java.net.URLEncoder.encode(englishQuery.trim(), "UTF-8")
                    + "&format=json&no_html=1&skip_disambig=1";
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .get()
                    .build();
            try (Response resp = client.newCall(request).execute()) {
                if (!resp.isSuccessful()) return null;
                String body = resp.body() != null ? resp.body().string() : "";
                return parseDdgResponse(body);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Wikipedia Search API（最大3件、スニペット＋最終更新日付き）
    private static String searchWikipedia(OkHttpClient client, String englishQuery) {
        try {
            String url = WIKI_API_URL
                    + "?action=query&list=search&srsearch="
                    + java.net.URLEncoder.encode(englishQuery.trim(), "UTF-8")
                    + "&srlimit=3&srprop=snippet%7Ctimestamp&utf8=1&format=json";
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
                JSONArray hits = json.getJSONObject("query").getJSONArray("search");
                if (hits.length() == 0) return null;

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(hits.length(), 3); i++) {
                    JSONObject hit = hits.getJSONObject(i);
                    String title = hit.optString("title", "").trim();
                    String snippet = hit.optString("snippet", "")
                            .replaceAll("<[^>]+>", "").trim();
                    String ts = hit.optString("timestamp", "");
                    String updated = ts.length() >= 10 ? ts.substring(0, 10) : ts;

                    if (title.isEmpty()) continue;
                    sb.append("[Wikipedia] ").append(title);
                    if (!updated.isEmpty()) sb.append(" (").append(updated).append(")");
                    sb.append("\n");
                    if (!snippet.isEmpty()) sb.append(snippet).append("\n");
                    sb.append("https://en.wikipedia.org/wiki/")
                      .append(java.net.URLEncoder.encode(title.replace(" ", "_"), "UTF-8"))
                      .append("\n");
                }
                String result = sb.toString().trim();
                return result.isEmpty() ? null : result;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String parseDdgResponse(String body) throws Exception {
        JSONObject json = new JSONObject(body);
        StringBuilder sb = new StringBuilder();

        String answer = json.optString("Answer", "").trim();
        if (!answer.isEmpty()) sb.append("[Answer] ").append(answer).append("\n");

        String abstractText = json.optString("AbstractText", "").trim();
        String abstractSource = json.optString("AbstractSource", "").trim();
        String abstractUrl = json.optString("AbstractURL", "").trim();
        if (!abstractText.isEmpty()) {
            sb.append("[").append(abstractSource.isEmpty() ? "Abstract" : abstractSource).append("] ");
            sb.append(abstractText).append("\n");
            if (!abstractUrl.isEmpty()) sb.append(abstractUrl).append("\n");
        }

        String definition = json.optString("Definition", "").trim();
        String definitionSource = json.optString("DefinitionSource", "").trim();
        if (!definition.isEmpty()) {
            sb.append("[").append(definitionSource.isEmpty() ? "Definition" : definitionSource).append("] ");
            sb.append(definition).append("\n");
        }

        JSONArray related = json.optJSONArray("RelatedTopics");
        if (related != null) {
            int count = 0;
            for (int i = 0; i < related.length() && count < 5; i++) {
                Object item = related.opt(i);
                if (!(item instanceof JSONObject)) continue;
                JSONObject topic = (JSONObject) item;
                String text = topic.optString("Text", "").trim();
                String firstUrl = topic.optString("FirstURL", "").trim();
                if (text.isEmpty()) continue;
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append("\n");
                sb.append("- ").append(text);
                if (!firstUrl.isEmpty()) sb.append("\n  ").append(firstUrl);
                sb.append("\n");
                count++;
            }
        }

        JSONArray results = json.optJSONArray("Results");
        if (results != null) {
            for (int i = 0; i < results.length() && i < 3; i++) {
                JSONObject result = results.optJSONObject(i);
                if (result == null) continue;
                String text = result.optString("Text", "").trim();
                String firstUrl = result.optString("FirstURL", "").trim();
                if (text.isEmpty()) continue;
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append("\n");
                sb.append("- ").append(text);
                if (!firstUrl.isEmpty()) sb.append("\n  ").append(firstUrl);
                sb.append("\n");
            }
        }

        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }
}
