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
 * Wikipedia Search API を使った無料Web検索。
 * 日本語版・英語版を並行検索し結果を統合する。
 * LLM でキーワードを抽出し（翻訳なし）、そのまま検索する。
 */
public final class WikipediaSearchHelper {

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private WikipediaSearchHelper() {}

    /**
     * ユーザー発話から検索キーワードを LLM で抽出する（日本語のまま、翻訳なし）。
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
                            "ユーザーのメッセージから検索キーワードのみを抽出してください。\n"
                            + "言語はそのまま（翻訳しない）。\n"
                            + "出力形式（どちらか一方のみ）:\n"
                            + "SEARCH: <キーワード>\n"
                            + "NONE\n"
                            + "説明・余計な出力は不要。"));
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", userInput));
            body.put("messages", messages);
            body.put("stream", false);
            body.put("options", new JSONObject().put("temperature", 0).put("num_predict", 60));

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
     * 日本語・英語 Wikipedia を検索し、結果を統合して返す。
     * 結果が得られなければ null を返す。
     */
    public static String search(OkHttpClient client, String query) {
        if (client == null || TextUtils.isEmpty(query)) return null;
        try {
            String jaPart = searchWikipedia(client, "ja", query);
            String enPart = searchWikipedia(client, "en", query);

            StringBuilder combined = new StringBuilder();
            if (jaPart != null) combined.append(jaPart);
            if (enPart != null) {
                if (combined.length() > 0) combined.append("\n");
                combined.append(enPart);
            }

            String result = combined.toString().trim();
            return result.isEmpty() ? null : "SEARCH_RESULTS:\n" + result;
        } catch (Exception e) {
            return null;
        }
    }

    // 検索ヒット一覧を取得し、各記事の冒頭全文（Summary REST API）を付加して返す
    private static String searchWikipedia(OkHttpClient client, String lang, String query) {
        try {
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
                if (!resp.isSuccessful()) return null;
                String body = resp.body() != null ? resp.body().string() : "";
                JSONObject json = new JSONObject(body);
                JSONArray hits = json.getJSONObject("query").getJSONArray("search");
                if (hits.length() == 0) return null;

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(hits.length(), 3); i++) {
                    JSONObject hit = hits.getJSONObject(i);
                    String title = hit.optString("title", "").trim();
                    String ts = hit.optString("timestamp", "");
                    String updated = ts.length() >= 10 ? ts.substring(0, 10) : ts;
                    if (title.isEmpty()) continue;

                    String articleUrl = "https://" + lang + ".wikipedia.org/wiki/"
                            + java.net.URLEncoder.encode(title.replace(" ", "_"), "UTF-8");

                    // 記事冒頭全文を Summary REST API で取得
                    String extract = fetchSummaryExtract(client, lang, title);

                    sb.append("[Wikipedia/").append(lang).append("] ").append(title);
                    if (!updated.isEmpty()) sb.append(" (").append(updated).append(")");
                    sb.append("\n");
                    if (extract != null && !extract.isEmpty()) sb.append(extract).append("\n");
                    sb.append(articleUrl).append("\n");
                }
                String result = sb.toString().trim();
                return result.isEmpty() ? null : result;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Wikipedia Summary REST API で記事冒頭テキスト（extract）を取得する
    private static String fetchSummaryExtract(OkHttpClient client, String lang, String title) {
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
                String extract = json.optString("extract", "").trim();
                // 長すぎる場合は先頭 1500 字に絞る（RAG が後段で再ランクするため）
                return extract.length() > 1500 ? extract.substring(0, 1500) : extract;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
