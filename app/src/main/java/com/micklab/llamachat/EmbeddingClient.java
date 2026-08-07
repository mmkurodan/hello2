package com.micklab.llamachat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Ollama 埋め込み生成クライアント。
 *
 * <p>新エンドポイント {@code POST /api/embed}（バッチ入力対応・レスポンス {@code embeddings})を優先し、
 * 旧サーバー向けに {@code POST /api/embeddings}（単一・レスポンス {@code embedding}）へ自動フォールバックする。</p>
 *
 * <p>同期メソッドのため、必ずバックグラウンドスレッドから呼び出すこと
 * （{@code client.newCall(req).execute()} を用いる）。</p>
 */
public final class EmbeddingClient {

    /** 埋め込みモデル未指定時に送るモデル名。"default" はサーバー側の現在ロード済みモデルを使う。 */
    public static final String DEFAULT_MODEL = "default";

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final String baseUrl;
    private final String model;

    public EmbeddingClient(OkHttpClient client, String baseUrl, String model) {
        this.client = client;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.model = (model == null || model.trim().isEmpty()) ? DEFAULT_MODEL : model.trim();
    }

    public String getModel() {
        return model;
    }

    /** 単一テキストの埋め込みベクトルを返す。失敗時は例外を投げる。 */
    public float[] embed(String text) throws IOException, JSONException {
        List<float[]> out = embedBatch(Collections.singletonList(text));
        if (out.isEmpty()) {
            throw new IOException("Empty embedding response");
        }
        return out.get(0);
    }

    /** 複数テキストの埋め込みをまとめて取得する。入力順に対応した結果リストを返す。 */
    public List<float[]> embedBatch(List<String> inputs) throws IOException, JSONException {
        if (inputs == null || inputs.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return embedViaApiEmbed(inputs);
        } catch (EndpointUnavailableException e) {
            // 旧 Ollama には /api/embed が無い → /api/embeddings に 1 件ずつフォールバック
            List<float[]> out = new ArrayList<>(inputs.size());
            for (String in : inputs) {
                out.add(embedViaLegacy(in));
            }
            return out;
        }
    }

    private List<float[]> embedViaApiEmbed(List<String> inputs) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray inputArr = new JSONArray();
        for (String in : inputs) {
            inputArr.put(in == null ? "" : in);
        }
        body.put("input", inputArr);

        Request request = new Request.Builder()
                .url(baseUrl + "/api/embed")
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .build();

        try (Response response = client.newCall(request).execute()) {
            // エンドポイント自体が無い（旧サーバー）場合はフォールバックへ
            if (response.code() == 404 || response.code() == 405) {
                throw new EndpointUnavailableException();
            }
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("/api/embed HTTP " + response.code() + ": " + respBody);
            }
            JSONObject json = new JSONObject(respBody);
            JSONArray embeddings = json.optJSONArray("embeddings");
            if (embeddings == null) {
                throw new IOException("/api/embed: missing 'embeddings' field");
            }
            List<float[]> out = new ArrayList<>(embeddings.length());
            for (int i = 0; i < embeddings.length(); i++) {
                out.add(toFloatArray(embeddings.getJSONArray(i)));
            }
            return out;
        }
    }

    private float[] embedViaLegacy(String input) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("prompt", input == null ? "" : input);

        Request request = new Request.Builder()
                .url(baseUrl + "/api/embeddings")
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("/api/embeddings HTTP " + response.code() + ": " + respBody);
            }
            JSONObject json = new JSONObject(respBody);
            JSONArray arr = json.optJSONArray("embedding");
            if (arr == null) {
                throw new IOException("/api/embeddings: missing 'embedding' field");
            }
            return toFloatArray(arr);
        }
    }

    private static float[] toFloatArray(JSONArray arr) throws JSONException {
        float[] v = new float[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            v[i] = (float) arr.getDouble(i);
        }
        return v;
    }

    private static String normalizeBaseUrl(String url) {
        String u = (url == null || url.trim().isEmpty()) ? "http://127.0.0.1:11434" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    // ===== ベクトルユーティリティ（純粋計算 / ユニットテスト対象） =====

    /** コサイン類似度（-1..1）。長さ不一致・空・零ベクトルは 0 を返す。 */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0f;
        }
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0f;
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    /** L2 正規化した新しい配列を返す。零ベクトル・空・null はそのまま（コピー）返す。 */
    public static float[] l2Normalize(float[] v) {
        if (v == null || v.length == 0) {
            return v;
        }
        double n = 0.0;
        for (float x : v) {
            n += (double) x * x;
        }
        n = Math.sqrt(n);
        if (n == 0.0) {
            return v.clone();
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / n);
        }
        return out;
    }

    /** /api/embed が存在しない旧サーバーを検知するための内部シグナル。 */
    private static final class EndpointUnavailableException extends IOException {
    }
}
