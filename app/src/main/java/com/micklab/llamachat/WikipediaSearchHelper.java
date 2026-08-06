package com.micklab.llamachat;

import android.text.TextUtils;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wikipedia Search API を使った無料Web検索。
 * 日本語版・英語版を並行検索し結果を統合する。
 * クエリはそのまま渡す（翻訳不要）。
 */
public final class WikipediaSearchHelper {

    private WikipediaSearchHelper() {}

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

    private static String searchWikipedia(OkHttpClient client, String lang, String query) {
        try {
            String url = "https://" + lang + ".wikipedia.org/w/api.php"
                    + "?action=query&list=search&srsearch="
                    + java.net.URLEncoder.encode(query.trim(), "UTF-8")
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
                    sb.append("[Wikipedia/").append(lang).append("] ").append(title);
                    if (!updated.isEmpty()) sb.append(" (").append(updated).append(")");
                    sb.append("\n");
                    if (!snippet.isEmpty()) sb.append(snippet).append("\n");
                    sb.append("https://").append(lang).append(".wikipedia.org/wiki/")
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
}
