package com.micklab.llamachat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * {@link SemanticExpertClassifier} の判定ロジック検証。
 *
 * <p>実埋め込みの代わりに「文字バイグラムのバッグ・オブ・ワーズ」を返す決定的なフェイク埋め込みを注入する。
 * 同一文はコサイン 1.0、語の重なりが多いほど高スコアになるため、閾値・マージン・利用可否除外・
 * キャッシュ・例外耐性といったロジックを決定的に検証できる。</p>
 */
public class SemanticExpertClassifierTest {

    /** 文字バイグラムを 512 次元へハッシュした決定的ベクトル。 */
    private static float[] bigramBow(String text) {
        float[] v = new float[512];
        if (text == null || text.isEmpty()) {
            return v;
        }
        if (text.length() == 1) {
            v[(text.charAt(0) & 0x7fffffff) % 512] += 1f;
            return v;
        }
        for (int i = 0; i + 1 < text.length(); i++) {
            int h = (text.charAt(i) * 31 + text.charAt(i + 1)) & 0x7fffffff;
            v[h % 512] += 1f;
        }
        return v;
    }

    private static final SemanticExpertClassifier.Embedder BOW = SemanticExpertClassifierTest::bigramBow;

    @Test
    public void exactWebExample_routesToWeb() {
        SemanticExpertClassifier c = new SemanticExpertClassifier();
        SemanticExpertClassifier.Result r = c.classify(
                "今日の天気を教えて", BOW, true, true, "test");
        assertEquals(r.debugText, ExpertType.WEB, r.expertType);
        assertTrue(r.score > 0.9f);
    }

    @Test
    public void exactMemorySaveExample_routesToMemorySave() {
        SemanticExpertClassifier c = new SemanticExpertClassifier();
        SemanticExpertClassifier.Result r = c.classify(
                "メモしておいて", BOW, true, true, "test");
        assertEquals(r.debugText, ExpertType.MEMORY_SAVE, r.expertType);
    }

    @Test
    public void greeting_routesToNone() {
        SemanticExpertClassifier c = new SemanticExpertClassifier();
        SemanticExpertClassifier.Result r = c.classify(
                "こんにちは", BOW, true, true, "test");
        assertEquals(r.debugText, ExpertType.NONE, r.expertType);
    }

    @Test
    public void webDisabled_doesNotRouteToWeb() {
        SemanticExpertClassifier c = new SemanticExpertClassifier();
        SemanticExpertClassifier.Result r = c.classify(
                "今日の天気を教えて", BOW, /*webAvailable=*/false, true, "test");
        assertNotEquals(ExpertType.WEB, r.expertType);
    }

    @Test
    public void memoryDisabled_doesNotRouteToMemory() {
        SemanticExpertClassifier c = new SemanticExpertClassifier();
        SemanticExpertClassifier.Result r = c.classify(
                "メモしておいて", BOW, true, /*memoryAvailable=*/false, "test");
        assertNotEquals(ExpertType.MEMORY_SAVE, r.expertType);
        assertEquals(ExpertType.NONE, r.expertType);
    }

    @Test
    public void emptyInput_routesToNone() {
        SemanticExpertClassifier c = new SemanticExpertClassifier();
        assertEquals(ExpertType.NONE, c.classify("  ", BOW, true, true, "test").expertType);
        assertEquals(ExpertType.NONE, c.classify(null, BOW, true, true, "test").expertType);
    }

    @Test
    public void nullEmbedder_routesToNoneWithoutCrash() {
        SemanticExpertClassifier c = new SemanticExpertClassifier();
        assertEquals(ExpertType.NONE, c.classify("今日の天気を教えて", null, true, true, "test").expertType);
    }

    @Test
    public void throwingEmbedder_routesToNoneWithoutCrash() {
        SemanticExpertClassifier.Embedder boom = text -> {
            throw new IOException("network down");
        };
        SemanticExpertClassifier c = new SemanticExpertClassifier();
        SemanticExpertClassifier.Result r = c.classify("今日の天気を教えて", boom, true, true, "test");
        assertEquals(ExpertType.NONE, r.expertType);
        assertTrue(r.debugText.contains("failed"));
    }

    @Test
    public void exampleEmbeddingsAreCachedAcrossCalls() {
        final int[] calls = {0};
        SemanticExpertClassifier.Embedder counting = text -> {
            calls[0]++;
            return bigramBow(text);
        };
        SemanticExpertClassifier c = new SemanticExpertClassifier();

        c.classify("今日の天気を教えて", counting, true, true, "modelA");
        int afterFirst = calls[0];
        c.classify("予定を登録して", counting, true, true, "modelA");
        int afterSecond = calls[0];

        // 2 回目は入力 1 件のみ埋め込む（代表発話はキャッシュ済み）。
        assertEquals(1, afterSecond - afterFirst);
        assertTrue(afterFirst > 1);
    }

    @Test
    public void changingCacheKeyRebuildsExamples() {
        final int[] calls = {0};
        SemanticExpertClassifier.Embedder counting = text -> {
            calls[0]++;
            return bigramBow(text);
        };
        SemanticExpertClassifier c = new SemanticExpertClassifier();

        c.classify("今日の天気を教えて", counting, true, true, "modelA");
        int afterFirst = calls[0];
        c.classify("今日の天気を教えて", counting, true, true, "modelB");
        int afterSecond = calls[0];

        // モデル鍵が変わったら代表発話を埋め込み直す（1 件より多い）。
        assertTrue(afterSecond - afterFirst > 1);
    }
}
