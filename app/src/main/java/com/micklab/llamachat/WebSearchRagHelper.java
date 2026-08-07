package com.micklab.llamachat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 埋め込みベクトルとOllama tokenize APIを用いた
 * Web検索結果のRAG再ランキングヘルパー。
 *
 * <p>検索結果をチャンク分割し、クエリとの埋め込みコサイン類似度で
 * 関連度の高い上位K件のみを抽出してLLMに渡すことで、
 * 関連性の低い情報のノイズを削減し応答品質を向上させる。</p>
 */
public final class WebSearchRagHelper {
    private static final int MAX_CHUNKS_TO_EMBED = 8;
    private static final int TOP_K_CHUNKS = 3;
    private static final int MAX_CONTEXT_TOKENS = 3000;
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final EmbeddingClient embeddingClient;
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String chatModel;

    public WebSearchRagHelper(EmbeddingClient embeddingClient, OkHttpClient httpClient,
                               String baseUrl, String chatModel) {
        this.embeddingClient = embeddingClient;
        this.httpClient = httpClient;
        this.baseUrl = normalizeUrl(baseUrl);
        this.chatModel = chatModel;
    }

    /**
     * 検索結果をRAG処理して関連度の高いチャンクのみを返す。
     * チャンク数がTOP_K以下、またはEmbeddingClientが利用不可の場合は
     * 元の検索結果をそのまま返す（フォールバック）。
     */
    public String rerankSearchResults(String userQuery, String rawSearchResults) {
        if (rawSearchResults == null || rawSearchResults.isEmpty()) return rawSearchResults;
        try {
            List<String> chunks = splitIntoChunks(rawSearchResults);
            if (chunks.size() <= TOP_K_CHUNKS) return rawSearchResults;

            int batchSize = Math.min(chunks.size(), MAX_CHUNKS_TO_EMBED);
            List<String> batchChunks = chunks.subList(0, batchSize);

            // クエリと全チャンクを1回のリクエストにまとめて埋め込む
            List<String> allTexts = new ArrayList<>(batchSize + 1);
            allTexts.add(userQuery);
            allTexts.addAll(batchChunks);
            List<float[]> allVecs = embeddingClient.embedBatch(allTexts);
            if (allVecs.size() < 2) return rawSearchResults;

            float[] queryVec = EmbeddingClient.l2Normalize(allVecs.get(0));
            List<float[]> chunkVecs = allVecs.subList(1, allVecs.size());

            List<ScoredChunk> scored = new ArrayList<>(chunkVecs.size());
            for (int i = 0; i < chunkVecs.size(); i++) {
                float sim = EmbeddingClient.cosineSimilarity(
                        queryVec, EmbeddingClient.l2Normalize(chunkVecs.get(i)));
                scored.add(new ScoredChunk(batchChunks.get(i), sim));
            }
            Collections.sort(scored, (a, b) -> Float.compare(b.score, a.score));

            StringBuilder result = new StringBuilder("SEARCH_RESULTS:\n");
            int budget = MAX_CONTEXT_TOKENS;
            int count = 0;
            for (ScoredChunk sc : scored) {
                if (count >= TOP_K_CHUNKS || budget <= 0) break;
                int tokens = countTokensSafe(sc.chunk);
                if (budget - tokens < 0 && count > 0) break;
                if (result.length() > "SEARCH_RESULTS:\n".length()) result.append("\n\n");
                result.append(sc.chunk);
                budget -= tokens;
                count++;
            }
            return result.toString();
        } catch (Exception e) {
            return rawSearchResults;
        }
    }

    /** Ollama /api/tokenize でトークン数を計算。失敗時は文字数推定で代替。 */
    public int countTokensSafe(String text) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", chatModel);
            body.put("prompt", text);
            Request req = new Request.Builder()
                    .url(baseUrl + "/api/tokenize")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA))
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) return estimateTokens(text);
                String respBody = resp.body() != null ? resp.body().string() : "";
                JSONArray tokens = new JSONObject(respBody).optJSONArray("tokens");
                return tokens != null ? tokens.length() : estimateTokens(text);
            }
        } catch (Exception e) {
            return estimateTokens(text);
        }
    }

    private List<String> splitIntoChunks(String searchResults) {
        List<String> chunks = new ArrayList<>();

        // Wikipedia 形式: [Wikipedia/lang] で記事単位に分割
        if (searchResults.contains("[Wikipedia/")) {
            String[] parts = searchResults.split("(?=\\[Wikipedia/)");
            for (String p : parts) {
                String t = p.trim();
                if (t.startsWith("[Wikipedia/")) chunks.add(t);
            }
            if (!chunks.isEmpty()) return chunks;
        }

        // Brave/generic 形式: [1] Title\nDesc\nURL で区切る
        String[] parts = searchResults.split("(?=\\[\\d+\\])");
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) chunks.add(t);
        }
        if (chunks.size() <= 1) {
            chunks.clear();
            for (String p : searchResults.split("\n\n+")) {
                String t = p.trim();
                if (!t.isEmpty()) chunks.add(t);
            }
        }
        return chunks;
    }

    private static int estimateTokens(String text) {
        if (text == null) return 0;
        return (int) (text.length() * 0.75);
    }

    private static String normalizeUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "http://127.0.0.1:11434";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static final class ScoredChunk {
        final String chunk;
        final float score;
        ScoredChunk(String chunk, float score) {
            this.chunk = chunk;
            this.score = score;
        }
    }
}
