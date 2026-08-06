package com.micklab.llamachat;

import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;

/**
 * 記憶の保存（LLM構造化抽出）・呼び出し（RAG）・通知用の予定/期限抽出を集約するヘルパー。
 *
 * <p>設定は実行時に変わり得るため、利用側は現在値で都度生成する。ネットワーク/DB を伴う
 * メソッドはワーカースレッドから呼び出すこと。</p>
 */
public final class MemoryFlowHelper {

    private final MemoryRepository repo;
    private final EmbeddingClient embedding; // null 可（その場合 LIKE 検索のみ）
    private final OkHttpClient client;
    private final String baseUrl;
    private final String model;
    private final StructuredOutput.Mode mode;
    private final String lang;

    private final SimpleDateFormat createdFmt =
            new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());

    public MemoryFlowHelper(MemoryRepository repo, EmbeddingClient embedding,
                            OkHttpClient client, String baseUrl, String model,
                            StructuredOutput.Mode mode, String appLanguage) {
        this.repo = repo;
        this.embedding = embedding;
        this.client = client;
        this.baseUrl = baseUrl;
        this.model = model;
        this.mode = mode;
        this.lang = "ja".equals(appLanguage) ? "ja" : "en";
    }

    // ===== 保存（LLM構造化抽出） =====

    /** 発話から分類・内容・計画日時を抽出して保存する。保存できたレコードを返す（内容空なら null）。 */
    public MemoryRecord extractAndSave(String userMsg) {
        if (repo == null) return null;
        MemoryExtractor.Extracted ex =
                MemoryExtractor.extract(client, baseUrl, model, mode, lang, userMsg);
        if (ex == null || TextUtils.isEmpty(ex.content)) return null;
        long created = System.currentTimeMillis();
        MemoryRecord rec = new MemoryRecord(0, ex.category, ex.content, created,
                ex.year, ex.month, ex.day, ex.hour, ex.minute, ex.location, false, null);
        long id = repo.save(rec);
        if (id < 0) return null;
        return new MemoryRecord(id, ex.category, ex.content, created,
                ex.year, ex.month, ex.day, ex.hour, ex.minute, ex.location, false, null);
    }

    /** 保存結果の確認文（分類＋内容＋予定ラベル）。 */
    public String confirmationText(MemoryRecord r) {
        if (r == null) return "";
        boolean ja = "ja".equals(lang);
        String catLabel = categoryLabel(r.category);
        String sched = r.scheduleLabel(lang);
        StringBuilder sb = new StringBuilder();
        sb.append(ja ? "覚えました" : "Got it").append("（").append(catLabel).append("）：「")
          .append(r.content).append("」");
        if (!TextUtils.isEmpty(sched)) {
            sb.append(ja ? " / 予定: " : " / when: ").append(sched);
        }
        if (!TextUtils.isEmpty(r.location)) {
            sb.append(" @").append(r.location);
        }
        return sb.toString();
    }

    // ===== 呼び出し（RAG） =====

    /** 関連記憶を検索・ランク付けし、LLMへ渡すコンテキスト文字列を返す（無ければ空文字）。 */
    public String buildRecallContext(String userMsg) {
        if (repo == null) return "";
        String query = stripRecallTriggers(userMsg);
        List<MemoryRecord> candidates = TextUtils.isEmpty(query)
                ? repo.getRecent(30) : repo.search(query);
        if (candidates.isEmpty()) candidates = repo.getRecent(30);
        if (candidates.isEmpty()) return "";
        List<MemoryRecord> ranked = candidates;
        if (candidates.size() > 5 && embedding != null) {
            ranked = rerank(userMsg, candidates);
        }
        int take = Math.min(10, ranked.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < take; i++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(formatRecordLine(ranked.get(i)));
        }
        return sb.toString();
    }

    private List<MemoryRecord> rerank(String userMsg, List<MemoryRecord> records) {
        try {
            float[] queryVec = EmbeddingClient.l2Normalize(embedding.embed(userMsg));
            List<String> contents = new ArrayList<>();
            for (MemoryRecord r : records) contents.add(embeddingText(r));
            List<float[]> vecs = embedding.embedBatch(contents);
            List<Scored> scored = new ArrayList<>();
            for (int i = 0; i < vecs.size() && i < records.size(); i++) {
                float sim = EmbeddingClient.cosineSimilarity(
                        queryVec, EmbeddingClient.l2Normalize(vecs.get(i)));
                scored.add(new Scored(records.get(i), sim));
            }
            Collections.sort(scored, (a, b) -> Float.compare(b.score, a.score));
            List<MemoryRecord> result = new ArrayList<>(scored.size());
            for (Scored s : scored) result.add(s.record);
            return result;
        } catch (Exception e) {
            return records;
        }
    }

    private String embeddingText(MemoryRecord r) {
        String sched = r.scheduleLabel(lang);
        return TextUtils.isEmpty(sched) ? r.content : (r.content + " " + sched);
    }

    // ===== 通知（予定 / 期限） =====

    /** 指定日の予定（PLAN 該当）と当日期限の未完了 ToDo を返す（終日→時刻順）。 */
    public List<MemoryRecord> agendaForDay(LocalDate day) {
        List<MemoryRecord> out = new ArrayList<>();
        for (MemoryRecord r : repo.getByCategory(MemoryRecord.CAT_PLAN)) {
            if (r.occursOn(day)) out.add(r);
        }
        for (MemoryRecord r : repo.getByCategory(MemoryRecord.CAT_TODO)) {
            if (!r.completed && day.equals(r.exactDate())) out.add(r);
        }
        sortByTime(out, day);
        return out;
    }

    /** 指定日より前が期限の未完了 ToDo（やり残し）。 */
    public List<MemoryRecord> overdueTodos(LocalDate day) {
        List<MemoryRecord> out = new ArrayList<>();
        for (MemoryRecord r : repo.getByCategory(MemoryRecord.CAT_TODO)) {
            LocalDate due = r.exactDate();
            if (!r.completed && due != null && due.isBefore(day)) out.add(r);
        }
        return out;
    }

    /** from から minutes 分以内に時刻が来る当日の予定/期限（直近リマインダ用）。 */
    public List<MemoryRecord> upcomingWithin(LocalDateTime from, int minutes) {
        LocalDate day = from.toLocalDate();
        LocalDateTime until = from.plusMinutes(minutes);
        List<MemoryRecord> out = new ArrayList<>();
        List<MemoryRecord> pool = new ArrayList<>();
        pool.addAll(repo.getByCategory(MemoryRecord.CAT_PLAN));
        for (MemoryRecord r : repo.getByCategory(MemoryRecord.CAT_TODO)) {
            if (!r.completed) pool.add(r);
        }
        for (MemoryRecord r : pool) {
            if (!r.occursOn(day) || !r.hasTime()) continue;
            LocalDateTime at = r.dateTimeOn(day);
            if (at != null && at.isAfter(from) && !at.isAfter(until)) out.add(r);
        }
        sortByTime(out, day);
        return out;
    }

    /** 予定/期限の一覧を箇条書きテキストにする（空リストなら空文字）。 */
    public String formatAgenda(List<MemoryRecord> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (MemoryRecord r : items) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("- ");
            String sched = r.scheduleLabel(lang);
            if (!TextUtils.isEmpty(sched)) sb.append(sched).append(" ");
            sb.append(r.content);
            if (!TextUtils.isEmpty(r.location)) sb.append(" @").append(r.location);
        }
        return sb.toString();
    }

    // ===== 内部 =====

    private void sortByTime(List<MemoryRecord> list, LocalDate day) {
        Collections.sort(list, new Comparator<MemoryRecord>() {
            @Override
            public int compare(MemoryRecord a, MemoryRecord b) {
                LocalDateTime ta = a.dateTimeOn(day);
                LocalDateTime tb = b.dateTimeOn(day);
                if (ta == null && tb == null) return 0;
                if (ta == null) return -1; // 終日を先頭
                if (tb == null) return 1;
                return ta.compareTo(tb);
            }
        });
    }

    private String formatRecordLine(MemoryRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(categoryLabel(r.category));
        String sched = r.scheduleLabel(lang);
        if (!TextUtils.isEmpty(sched)) sb.append(" ").append(sched);
        if (r.isTodo()) {
            boolean ja = "ja".equals(lang);
            sb.append(r.completed ? (ja ? " 完了" : " done") : (ja ? " 未完了" : " open"));
        }
        if (!TextUtils.isEmpty(r.location)) sb.append(" @").append(r.location);
        sb.append(" | ").append("ja".equals(lang) ? "登録 " : "saved ")
          .append(createdFmt.format(new Date(r.createdAt)));
        sb.append("] ").append(r.content);
        return sb.toString();
    }

    private String categoryLabel(String category) {
        boolean ja = "ja".equals(lang);
        if (MemoryRecord.CAT_TODO.equals(category)) return ja ? "ToDo" : "TODO";
        if (MemoryRecord.CAT_PLAN.equals(category)) return ja ? "予定" : "PLAN";
        return ja ? "メモ" : "MEMO";
    }

    private static String stripRecallTriggers(String userMsg) {
        if (TextUtils.isEmpty(userMsg)) return "";
        String result = userMsg;
        String[] triggers = {
                "思い出してください", "思い出して",
                "覚えていますか", "覚えてる？", "覚えてる",
                "覚えていた？", "覚えていた",
                "記憶にある？", "記憶にある",
                "記録を見せてください", "記録を見せて",
                "メモを見せてください", "メモを見せて",
                "記憶を調べてください", "記憶を調べて",
                "記録はある？", "記録はある",
                "メモを確認して"
        };
        for (String trigger : triggers) {
            result = result.replace(trigger, "").trim();
        }
        result = result.replaceAll("について$|のことを$|のこと$|を$|が$|は$", "").trim();
        return result.trim();
    }

    private static final class Scored {
        final MemoryRecord record;
        final float score;
        Scored(MemoryRecord record, float score) {
            this.record = record;
            this.score = score;
        }
    }
}
