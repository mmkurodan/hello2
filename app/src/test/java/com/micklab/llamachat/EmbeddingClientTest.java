package com.micklab.llamachat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link EmbeddingClient} の純粋計算ユーティリティ（コサイン類似度・L2 正規化）の検証。
 * HTTP / JSON 経路は Android スタブを踏むためここでは対象外。
 */
public class EmbeddingClientTest {

    private static final float EPS = 1e-5f;

    @Test
    public void cosineSimilarity_identicalVectors_isOne() {
        float[] a = {1f, 2f, 3f};
        assertEquals(1.0f, EmbeddingClient.cosineSimilarity(a, a), EPS);
    }

    @Test
    public void cosineSimilarity_orthogonalVectors_isZero() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};
        assertEquals(0.0f, EmbeddingClient.cosineSimilarity(a, b), EPS);
    }

    @Test
    public void cosineSimilarity_oppositeVectors_isMinusOne() {
        float[] a = {1f, 1f};
        float[] b = {-1f, -1f};
        assertEquals(-1.0f, EmbeddingClient.cosineSimilarity(a, b), EPS);
    }

    @Test
    public void cosineSimilarity_isScaleInvariant() {
        float[] a = {1f, 2f, 3f};
        float[] b = {10f, 20f, 30f};
        assertEquals(1.0f, EmbeddingClient.cosineSimilarity(a, b), EPS);
    }

    @Test
    public void cosineSimilarity_lengthMismatch_isZero() {
        assertEquals(0.0f, EmbeddingClient.cosineSimilarity(new float[]{1f}, new float[]{1f, 2f}), EPS);
    }

    @Test
    public void cosineSimilarity_zeroVector_isZero() {
        assertEquals(0.0f, EmbeddingClient.cosineSimilarity(new float[]{0f, 0f}, new float[]{1f, 1f}), EPS);
    }

    @Test
    public void cosineSimilarity_nullInputs_isZero() {
        assertEquals(0.0f, EmbeddingClient.cosineSimilarity(null, new float[]{1f}), EPS);
        assertEquals(0.0f, EmbeddingClient.cosineSimilarity(new float[]{1f}, null), EPS);
    }

    @Test
    public void l2Normalize_producesUnitLength() {
        float[] n = EmbeddingClient.l2Normalize(new float[]{3f, 4f});
        double len = Math.sqrt(n[0] * n[0] + n[1] * n[1]);
        assertEquals(1.0, len, EPS);
        assertEquals(0.6f, n[0], EPS);
        assertEquals(0.8f, n[1], EPS);
    }

    @Test
    public void l2Normalize_zeroVector_staysZero() {
        float[] n = EmbeddingClient.l2Normalize(new float[]{0f, 0f});
        assertEquals(0.0f, n[0], EPS);
        assertEquals(0.0f, n[1], EPS);
    }

    @Test
    public void l2Normalize_thenCosine_matchesRawCosine() {
        float[] a = {0.2f, -1.3f, 4.0f, 0.5f};
        float[] b = {1.1f, 0.0f, -2.2f, 3.3f};
        float raw = EmbeddingClient.cosineSimilarity(a, b);
        float norm = EmbeddingClient.cosineSimilarity(
                EmbeddingClient.l2Normalize(a), EmbeddingClient.l2Normalize(b));
        assertTrue(Math.abs(raw - norm) < 1e-4f);
    }
}
