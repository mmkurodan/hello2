package com.micklab.llamachat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 埋め込み（コサイン類似度）によるエキスパート分類器。
 *
 * <p>キーワード一致（{@link ExpertSelector}）が何も拾えなかったときの<strong>意味的フォールバック</strong>として使う。
 * 各エキスパートの代表発話をあらかじめ埋め込み、ユーザ入力との最大コサイン類似度で判定する。
 * 言い換え・表記揺れにロバストで、キーワード辞書に無い表現も拾える。</p>
 *
 * <p>埋め込み取得は {@link Embedder} として注入するため、ネットワークに依存せずユニットテスト可能。
 * 判定は閾値（threshold）とトップ2差（margin）で保守的に行い、曖昧なときは {@link ExpertType#NONE}
 * （＝通常チャット）を返してキーワード結果を崩さない。</p>
 */
public final class SemanticExpertClassifier {

    /**
     * 埋め込み取得関数。バックグラウンドスレッドで呼ばれる前提。失敗時は例外。
     * {@link EmbeddingClient#embed(String)} は {@code IOException} と {@code JSONException} を投げるため
     * {@code throws Exception} とし、{@link #classify} 側でまとめて捕捉する。
     */
    public interface Embedder {
        float[] embed(String text) throws Exception;
    }

    /** 分類結果。{@link ExpertType#NONE} は「エキスパート不要＝通常チャット」を表す。 */
    public static final class Result {
        public final ExpertType expertType;
        public final float score;
        public final String debugText;

        Result(ExpertType expertType, float score, String debugText) {
            this.expertType = expertType == null ? ExpertType.NONE : expertType;
            this.score = score;
            this.debugText = debugText == null ? "" : debugText;
        }
    }

    private static final Map<ExpertType, String[]> EXAMPLES = buildExamples();

    private final float threshold;
    private final float margin;

    // 代表発話の埋め込みキャッシュ（モデルが変わったら破棄）。
    private final Map<ExpertType, float[][]> cache = new LinkedHashMap<>();
    private String cachedKey = null;

    /**
     * デフォルト値。
     * - threshold: best スコアがこれ以上であること（生成系モデルは全体が高値なので低めに設定）。
     * - margin: best と second の差の「best に対する比率（相対マージン）」。
     *   絶対値（0.04 等）はスコアが密集する生成系モデルで常に失敗するため相対値を使う。
     *   1.5% = 0.015: 「今日の予定」vs「削除」で 2.2% の差なら通過、同点付近は NONE。
     */
    public SemanticExpertClassifier() {
        this(0.60f, 0.015f);
    }

    public SemanticExpertClassifier(float threshold, float margin) {
        this.threshold = threshold;
        this.margin = margin;
    }

    /**
     * ユーザ入力を分類する。利用不可なエキスパート（web/calendar 無効）は候補から除外する。
     * 埋め込み取得に失敗しても例外は投げず {@link ExpertType#NONE} を返す（チャットを止めない）。
     *
     * @param cacheKey 代表発話キャッシュの鍵（通常は埋め込みモデル名）。変わるとキャッシュを作り直す。
     */
    public Result classify(String userInput,
                           Embedder embedder,
                           boolean webAvailable,
                           boolean calendarAvailable,
                           String cacheKey) {
        if (userInput == null || userInput.trim().isEmpty() || embedder == null) {
            return new Result(ExpertType.NONE, 0f, "semantic: empty input or no embedder");
        }
        try {
            ensureExampleCache(embedder, cacheKey);
            float[] q = EmbeddingClient.l2Normalize(embedder.embed(userInput));

            List<String> trace = new ArrayList<>();
            ExpertType best = ExpertType.NONE;
            float bestScore = -1f;
            ExpertType second = ExpertType.NONE;
            float secondScore = -1f;
            for (Map.Entry<ExpertType, float[][]> e : cache.entrySet()) {
                ExpertType type = e.getKey();
                if (!isAvailable(type, webAvailable, calendarAvailable)) {
                    continue;
                }
                float s = maxCosine(q, e.getValue());
                trace.add(type.name() + " -> " + fmt(s));
                if (s > bestScore) {
                    second = best;
                    secondScore = bestScore;
                    best = type;
                    bestScore = s;
                } else if (s > secondScore) {
                    second = type;
                    secondScore = s;
                }
            }

            // 相対マージン: (best - second) / best >= margin
            // 絶対マージンは生成系モデル（スコアが 0.93〜0.99 に密集）で常に失敗するため、
            // best スコアに対する比率で判定する。
            float relMargin = (bestScore > 0f) ? (bestScore - secondScore) / bestScore : 0f;
            boolean accepted = best != ExpertType.NONE
                    && bestScore >= threshold
                    && relMargin >= margin;
            ExpertType decided = accepted ? best : ExpertType.NONE;

            String debug = "semantic routing (threshold=" + fmt(threshold)
                    + ", rel_margin=" + fmt(margin) + ")\n"
                    + String.join("\n", trace) + "\n"
                    + "best=" + best.name() + " score=" + fmt(bestScore)
                    + ", second=" + second.name() + " score=" + fmt(secondScore)
                    + ", rel_margin=" + fmt(relMargin)
                    + " -> " + (accepted ? decided.name() : "NONE (below threshold or ambiguous)");
            return new Result(decided, bestScore, debug);
        } catch (Exception ex) {
            return new Result(ExpertType.NONE, 0f, "semantic routing failed: " + ex.getMessage());
        }
    }

    private void ensureExampleCache(Embedder embedder, String cacheKey) throws Exception {
        String key = cacheKey == null ? "" : cacheKey;
        if (key.equals(cachedKey) && !cache.isEmpty()) {
            return;
        }
        cache.clear();
        for (Map.Entry<ExpertType, String[]> e : EXAMPLES.entrySet()) {
            String[] examples = e.getValue();
            float[][] vecs = new float[examples.length][];
            for (int i = 0; i < examples.length; i++) {
                vecs[i] = EmbeddingClient.l2Normalize(embedder.embed(examples[i]));
            }
            cache.put(e.getKey(), vecs);
        }
        cachedKey = key;
    }

    private static boolean isAvailable(ExpertType type, boolean web, boolean cal) {
        if (type == ExpertType.WEB) {
            return web;
        }
        if (isCalendar(type)) {
            return cal;
        }
        return true; // NONE, MEMORY_SAVE, MEMORY_RECALL は常に候補
    }

    private static boolean isCalendar(ExpertType t) {
        return t == ExpertType.CALENDAR_CREATE
                || t == ExpertType.CALENDAR_QUERY
                || t == ExpertType.CALENDAR_UPDATE
                || t == ExpertType.CALENDAR_DELETE;
    }

    private static float maxCosine(float[] q, float[][] examples) {
        float best = -1f;
        for (float[] ex : examples) {
            float s = EmbeddingClient.cosineSimilarity(q, ex);
            if (s > best) {
                best = s;
            }
        }
        return best;
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static Map<ExpertType, String[]> buildExamples() {
        Map<ExpertType, String[]> m = new LinkedHashMap<>();
        m.put(ExpertType.WEB, new String[]{
                "最新のニュースを調べて",
                "今日の天気を教えて",
                "ネットで検索して",
                "この製品の価格を調べて",
                "最近の出来事について教えて",
                "ウェブで確認して",
                "検索して最新情報を出して"
        });
        m.put(ExpertType.CALENDAR_CREATE, new String[]{
                "明日の15時に会議を入れて",
                "予定を登録して",
                "来週の打ち合わせを追加して",
                "金曜の夜に予定を入れたい",
                "カレンダーに予定を作成して",
                "土曜に歯医者の予約を入れて"
        });
        m.put(ExpertType.CALENDAR_QUERY, new String[]{
                "今日の予定を教えて",
                "明日のスケジュールを確認して",
                "今週の予定はある",
                "カレンダーを見せて",
                "次の予定は何",
                "空いている時間を教えて"
        });
        m.put(ExpertType.CALENDAR_UPDATE, new String[]{
                "会議の時間を変更して",
                "予定を1時間ずらして",
                "打ち合わせを午後に移動して",
                "予定の時刻を変えて",
                "明日の会議を3時に直して"
        });
        m.put(ExpertType.CALENDAR_DELETE, new String[]{
                "明日の予定を削除して",
                "会議をキャンセルして",
                "その予定を消して",
                "予定を取り消して",
                "歯医者の予約を取りやめて"
        });
        m.put(ExpertType.MEMORY_SAVE, new String[]{
                "これを覚えておいて",
                "メモしておいて",
                "記録しておいて",
                "覚えておいてください",
                "今日のランチはカレーだったことを記憶して",
                "大切なことをメモして"
        });
        m.put(ExpertType.MEMORY_RECALL, new String[]{
                "さっき言ったこと思い出して",
                "前に覚えてもらったことを教えて",
                "記録を確認して",
                "覚えてる？",
                "メモを見せて",
                "記憶を呼び戻して"
        });
        m.put(ExpertType.NONE, new String[]{
                "こんにちは",
                "ありがとう",
                "元気",
                "あなたの名前は",
                "面白い話をして",
                "おはよう",
                "少し雑談しよう",
                "おすすめの映画は"
        });
        return m;
    }
}
