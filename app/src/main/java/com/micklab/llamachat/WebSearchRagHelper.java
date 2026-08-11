package com.micklab.llamachat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
    // クイック事前絞り込み後に embedding へ渡す最大チャンク数
    private static final int MAX_CHUNKS_TO_EMBED = 20;
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final EmbeddingClient embeddingClient;
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String chatModel;
    private final int maxContextTokens;
    private final int topKChunks;

    /**
     * @param numCtx LLM のコンテキストサイズ。検索結果の token 予算と top-K を動的に決定する。
     *               0 以下の場合はデフォルト (4096) を使用。
     */
    public WebSearchRagHelper(EmbeddingClient embeddingClient, OkHttpClient httpClient,
                               String baseUrl, String chatModel, int numCtx) {
        this.embeddingClient = embeddingClient;
        this.httpClient = httpClient;
        this.baseUrl = normalizeUrl(baseUrl);
        this.chatModel = chatModel;
        int ctx = numCtx > 0 ? numCtx : 4096;
        // nCtx の約 55% を検索結果に割り当て（残りはシステムプロンプト・履歴・ユーザ発話）
        this.maxContextTokens = Math.max(500, (int) (ctx * 0.55));
        // nCtx 1024 あたり 1 チャンク、最小 2、最大 10
        this.topKChunks = Math.max(2, Math.min(10, ctx / 1024));
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
            if (chunks.size() <= topKChunks) return rawSearchResults;

            // ① 速度最適化: キーワード頻度と時制スコアで事前絞り込み → embedding 対象を削減
            List<String> candidates = quickPrefilter(chunks, userQuery, MAX_CHUNKS_TO_EMBED);

            // ② 時制認識: クエリに現在年月を付加して「今年」「最近」等が正しく解決されるよう補助
            String embeddingQuery = augmentWithDate(userQuery);

            List<String> allTexts = new ArrayList<>(candidates.size() + 1);
            allTexts.add(embeddingQuery);
            allTexts.addAll(candidates);
            List<float[]> allVecs = embeddingClient.embedBatch(allTexts);
            if (allVecs.size() < 2) return rawSearchResults;

            float[] queryVec = EmbeddingClient.l2Normalize(allVecs.get(0));
            List<float[]> chunkVecs = allVecs.subList(1, allVecs.size());

            List<ScoredChunk> scored = new ArrayList<>(chunkVecs.size());
            for (int i = 0; i < chunkVecs.size(); i++) {
                float sim = EmbeddingClient.cosineSimilarity(
                        queryVec, EmbeddingClient.l2Normalize(chunkVecs.get(i)));
                scored.add(new ScoredChunk(candidates.get(i), sim));
            }
            Collections.sort(scored, (a, b) -> Float.compare(b.score, a.score));

            StringBuilder result = new StringBuilder("SEARCH_RESULTS:\n");
            int budget = maxContextTokens;
            int count = 0;
            for (ScoredChunk sc : scored) {
                if (count >= topKChunks || budget <= 0) break;
                // ③ 速度最適化: HTTP tokenize API を呼ばず文字数推定でバジェット管理
                int tokens = estimateTokens(sc.chunk);
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

    /**
     * クエリに現在の年月を付加する。
     * 「今年」「最近」等の時制表現が embedding でも正しく解釈されるよう補助する。
     */
    private static String augmentWithDate(String query) {
        LocalDate today = LocalDate.now();
        return query + " " + today.getYear() + "年" + today.getMonthValue() + "月";
    }

    /**
     * embedding 前の高速事前絞り込み。
     * クエリトークンの出現頻度でスコアリングし、時制クエリ（「今年」等）では
     * 現在年を含むセクション見出しを大幅ブーストして maxChunks 件に絞る。
     */
    private static List<String> quickPrefilter(List<String> chunks, String query, int maxChunks) {
        if (chunks.size() <= maxChunks) return chunks;

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        String[] tokens = lowerQuery.split("[\\s　、。，．！？!?,]+");

        int currentYear = LocalDate.now().getYear();
        boolean temporalQuery = lowerQuery.contains("今年") || lowerQuery.contains("今月")
                || lowerQuery.contains("最近") || lowerQuery.contains("現在")
                || lowerQuery.contains("this year") || lowerQuery.contains("current")
                || lowerQuery.contains("latest") || lowerQuery.contains("recent");

        List<ScoredChunk> scored = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            String lowerChunk = chunk.toLowerCase(Locale.ROOT);
            float score = 0;
            for (String token : tokens) {
                if (token.length() < 2) continue;
                int idx = 0;
                while ((idx = lowerChunk.indexOf(token, idx)) >= 0) {
                    score++;
                    idx += token.length();
                }
            }
            // 時制クエリ時: 現在年を含むセクション見出し行を強くブースト
            if (temporalQuery) {
                String header = chunk.split("\n")[0];
                if (header.contains(String.valueOf(currentYear))) score += 10;
            }
            scored.add(new ScoredChunk(chunk, score));
        }
        Collections.sort(scored, (a, b) -> Float.compare(b.score, a.score));

        List<String> result = new ArrayList<>(maxChunks);
        for (int i = 0; i < maxChunks; i++) {
            result.add(scored.get(i).chunk);
        }
        return result;
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
