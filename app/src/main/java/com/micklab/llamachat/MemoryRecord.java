package com.micklab.llamachat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;

/**
 * 記憶レコード。分類（Memo / ToDo / Plan）と、任意の計画日時・場所・完了状態を持つ。
 *
 * <p>必須項目は {@code category}・{@code content}・{@code createdAt}（登録日時）。
 * 計画日時 {@code planYear/Month/Day/Hour/Minute} はいずれも null 許容で、null は「ブランク」を表す。</p>
 *
 * <ul>
 *   <li><b>PLAN（予定）</b>: 年 null=毎年 / 年月 null=毎月 / 年月日 null=毎日、時刻 null=終日。
 *       日付・時刻がすべて null なら繰り返しではなく単なる予定メモ（スケジュールなし）。場所は任意。</li>
 *   <li><b>TODO（やること）</b>: 期限として年月日を必須とするワンタイム。時刻・場所は任意。完了状態を持つ。</li>
 *   <li><b>MEMO（メモ）</b>: 単なる事実・メモ。スケジュールを持たない。</li>
 * </ul>
 */
public final class MemoryRecord {

    public static final String CAT_MEMO = "MEMO";
    public static final String CAT_TODO = "TODO";
    public static final String CAT_PLAN = "PLAN";

    public final long id;
    public final String category;      // CAT_MEMO / CAT_TODO / CAT_PLAN
    public final String content;
    public final long createdAt;        // 登録日時（epoch millis）
    public final Integer planYear;
    public final Integer planMonth;
    public final Integer planDay;
    public final Integer planHour;
    public final Integer planMinute;
    public final String location;
    public final boolean completed;     // ToDo の完了状態
    public final String tags;

    public MemoryRecord(long id, String category, String content, long createdAt,
                        Integer planYear, Integer planMonth, Integer planDay,
                        Integer planHour, Integer planMinute,
                        String location, boolean completed, String tags) {
        this.id = id;
        this.category = normalizeCategory(category);
        this.content = content != null ? content : "";
        this.createdAt = createdAt;
        this.planYear = planYear;
        this.planMonth = planMonth;
        this.planDay = planDay;
        this.planHour = planHour;
        this.planMinute = planMinute;
        this.location = location != null ? location : "";
        this.completed = completed;
        this.tags = tags != null ? tags : "";
    }

    public static String normalizeCategory(String c) {
        if (c == null) return CAT_MEMO;
        String u = c.trim().toUpperCase(Locale.ROOT);
        if (CAT_TODO.equals(u) || CAT_PLAN.equals(u)) return u;
        return CAT_MEMO;
    }

    public boolean isMemo() { return CAT_MEMO.equals(category); }
    public boolean isTodo() { return CAT_TODO.equals(category); }
    public boolean isPlan() { return CAT_PLAN.equals(category); }

    /** 月日がそろっている（日付として扱える）。 */
    public boolean hasDate() { return planMonth != null && planDay != null; }

    /** 時刻がそろっている。 */
    public boolean hasTime() { return planHour != null && planMinute != null; }

    /** 計画関連の項目が一つも無い（スケジュールなし）。 */
    public boolean hasNoSchedule() {
        return planYear == null && planMonth == null && planDay == null
                && planHour == null && planMinute == null;
    }

    /** 年がブランクで月日または時刻が指定されている（毎年 / 毎月 / 毎日）。 */
    public boolean isRecurring() {
        return planYear == null && !hasNoSchedule();
    }

    /** 指定日にこの計画が該当するか（繰り返し・ワンタイム双方を考慮）。 */
    public boolean occursOn(LocalDate day) {
        if (day == null || hasNoSchedule()) return false;
        if (planYear == null && planMonth == null && planDay == null) {
            return hasTime(); // 時刻付きの毎日
        }
        if (planDay == null || planDay != day.getDayOfMonth()) return false;
        if (planMonth != null && planMonth != day.getMonthValue()) return false;
        return planYear == null || planYear == day.getYear();
    }

    /** 時刻を持つ場合、指定日における計画日時を返す（時刻が無ければ null）。 */
    public LocalDateTime dateTimeOn(LocalDate day) {
        if (day == null || !hasTime()) return null;
        return LocalDateTime.of(day, LocalTime.of(planHour, planMinute));
    }

    /** ワンタイム（年月日すべて確定）の期限日を返す。確定していなければ null。 */
    public LocalDate exactDate() {
        if (planYear == null || planMonth == null || planDay == null) return null;
        try {
            return LocalDate.of(planYear, planMonth, planDay);
        } catch (Exception e) {
            return null;
        }
    }

    /** 予定・期限を人が読める短いラベルにする（スケジュールなしは空文字）。 */
    public String scheduleLabel(String lang) {
        boolean ja = "ja".equals(lang);
        if (hasNoSchedule()) return "";
        StringBuilder sb = new StringBuilder();
        if (planYear == null && planMonth == null && planDay == null) {
            if (hasTime()) sb.append(ja ? "毎日" : "daily");
        } else if (planDay != null) {
            if (planMonth == null) {
                sb.append(ja ? ("毎月" + planDay + "日")
                             : ("monthly day " + planDay));
            } else if (planYear == null) {
                sb.append(ja ? ("毎年" + planMonth + "月" + planDay + "日")
                             : String.format(Locale.US, "yearly %02d/%02d", planMonth, planDay));
            } else {
                sb.append(ja ? (planYear + "年" + planMonth + "月" + planDay + "日")
                             : String.format(Locale.US, "%04d/%02d/%02d", planYear, planMonth, planDay));
            }
        }
        if (hasTime()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(String.format(Locale.US, "%02d:%02d", planHour, planMinute));
        } else if (sb.length() > 0) {
            sb.append(ja ? "（終日）" : " (all-day)");
        }
        return sb.toString();
    }
}
