package com.micklab.llamachat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ExpertSelector {
    private static final String[] WEB_KEYWORDS = new String[]{
            "web", "ウェブ", "インターネット", "検索", "ググる", "調べて"
    };
    private static final String[] MEMORY_SAVE_KEYWORDS = new String[]{
            "覚えておいて", "覚えておく", "記録しておいて", "メモしておいて", "記憶しておいて",
            "メモして", "記録して", "記憶して"
    };
    private static final String[] MEMORY_RECALL_KEYWORDS = new String[]{
            "思い出して", "覚えてる", "覚えていた", "記憶にある", "記録を見せて",
            "メモを見せて", "記憶を調べて", "覚えていますか", "記録はある", "メモを確認"
    };
    private static final String[] CALENDAR_CREATE_KEYWORDS = new String[]{
            "予定を入れたい",
            "予定したい",
            "予定する",
            "登録して",
            "追加して",
            "入れて",
            "予定して欲しい",
            "予定してほしい"
    };
    private static final String[] CALENDAR_QUERY_KEYWORDS = new String[]{
            "予定を検索", "予定を探", "予定を確認", "予定を見せ", "予定を教えて",
            "予定ある", "カレンダーを確認", "カレンダーを見せ", "スケジュールを確認"
    };
    private static final String[] CALENDAR_UPDATE_KEYWORDS = new String[]{
            "予定を変える", "変更する", "ずらす", "移動する", "時間を変える"
    };
    private static final String[] CALENDAR_DELETE_KEYWORDS = new String[]{
            "予定を削除", "予定を消す", "予定を取り消す", "予定をやめる"
    };
    private static final String[] CALENDAR_CONTEXT_KEYWORDS = new String[]{
            "予定", "カレンダー", "スケジュール", "日程"
    };
    private static final String[] CALENDAR_UPDATE_VERBS = new String[]{
            "変える", "変更", "ずらす", "移動", "時間を変える"
    };
    private static final String[] CALENDAR_DELETE_VERBS = new String[]{
            "削除", "消す", "取り消す", "やめる"
    };

    public ExpertType select(String userInput, boolean webAvailable, boolean calendarAvailable, boolean memoryAvailable) {
        List<ExpertType> ordered = selectAll(userInput, webAvailable, calendarAvailable, memoryAvailable);
        if (ordered.isEmpty()) {
            return ExpertType.NONE;
        }
        return ordered.get(0);
    }

    public List<ExpertType> selectAll(String userInput, boolean webAvailable, boolean calendarAvailable, boolean memoryAvailable) {
        return selectDetailed(userInput, webAvailable, calendarAvailable, memoryAvailable).getOrderedExpertTypes();
    }

    public SelectionResult selectDetailed(String userInput, boolean webAvailable, boolean calendarAvailable, boolean memoryAvailable) {
        String normalized = normalize(userInput);
        if (normalized.isEmpty()) {
            return new SelectionResult(Collections.emptyList(), buildEmptyDebugText(userInput, normalized));
        }

        // 記憶キーワードは有効時のみ検出（web/calendarと同様にゲート）
        if (memoryAvailable) {
            KeywordMatch memorySaveMatch = firstKeywordMatch(normalized, MEMORY_SAVE_KEYWORDS);
            if (memorySaveMatch != null) {
                return new SelectionResult(
                        Collections.singletonList(ExpertType.MEMORY_SAVE),
                        "memory_save keyword: \"" + memorySaveMatch.keyword + "\"");
            }
            KeywordMatch memoryRecallMatch = firstKeywordMatch(normalized, MEMORY_RECALL_KEYWORDS);
            if (memoryRecallMatch != null) {
                return new SelectionResult(
                        Collections.singletonList(ExpertType.MEMORY_RECALL),
                        "memory_recall keyword: \"" + memoryRecallMatch.keyword + "\"");
            }
        }

        List<MatchedExpert> matches = new ArrayList<>();
        List<String> traceLines = new ArrayList<>();
        if (calendarAvailable) {
            CalendarSelection calendarSelection = findCalendarSelection(normalized);
            traceLines.addAll(calendarSelection.getTraceLines());
            if (calendarSelection.getMatchedExpert() != null) {
                matches.add(calendarSelection.getMatchedExpert());
            }
        } else {
            traceLines.add("calendar disabled");
        }
        if (webAvailable) {
            KeywordMatch webMatch = firstWebKeywordMatch(normalized);
            if (webMatch != null) {
                matches.add(new MatchedExpert(
                        ExpertType.WEB,
                        webMatch.position,
                        "web keyword",
                        webMatch.keyword
                ));
                traceLines.add("web -> pos=" + webMatch.position + ", keyword=\"" + webMatch.keyword + "\"");
            } else {
                traceLines.add("web -> no match");
            }
        } else {
            traceLines.add("web disabled");
        }
        if (matches.isEmpty()) {
            return new SelectionResult(Collections.emptyList(), buildDebugText(userInput, normalized, traceLines, Collections.emptyList()));
        }

        matches.sort(Comparator.comparingInt(match -> match.position));
        List<ExpertType> ordered = new ArrayList<>();
        List<String> orderedDetails = new ArrayList<>();
        for (MatchedExpert match : matches) {
            if (!ordered.contains(match.expertType)) {
                ordered.add(match.expertType);
                orderedDetails.add(match.expertType.name()
                        + " @ " + match.position
                        + " [" + match.reason + ": " + match.keyword + "]");
            }
        }
        return new SelectionResult(ordered, buildDebugText(userInput, normalized, traceLines, orderedDetails));
    }

    private CalendarSelection findCalendarSelection(String normalized) {
        List<String> traceLines = new ArrayList<>();
        KeywordMatch createKeyword = firstKeywordMatch(normalized, CALENDAR_CREATE_KEYWORDS);
        KeywordMatch updateKeyword = firstKeywordMatch(normalized, CALENDAR_UPDATE_KEYWORDS);
        KeywordMatch deleteKeyword = firstKeywordMatch(normalized, CALENDAR_DELETE_KEYWORDS);
        KeywordMatch queryKeyword = firstKeywordMatch(normalized, CALENDAR_QUERY_KEYWORDS);
        KeywordMatch contextKeyword = firstKeywordMatch(normalized, CALENDAR_CONTEXT_KEYWORDS);
        KeywordMatch updateVerb = firstCalendarVerbMatch(normalized, CALENDAR_UPDATE_VERBS);
        KeywordMatch deleteVerb = firstCalendarVerbMatch(normalized, CALENDAR_DELETE_VERBS);

        traceLines.add(formatCalendarTraceLine("calendar create", createKeyword));
        traceLines.add(formatCalendarTraceLine("calendar query", queryKeyword));
        traceLines.add(formatCalendarTraceLine("calendar update", updateKeyword != null ? updateKeyword : updateVerb));
        traceLines.add(formatCalendarTraceLine("calendar delete", deleteKeyword != null ? deleteKeyword : deleteVerb));
        traceLines.add(formatCalendarTraceLine("calendar context", contextKeyword));

        List<MatchedExpert> candidates = new ArrayList<>();
        addCalendarCandidate(candidates, ExpertType.CALENDAR_CREATE, createKeyword, contextKeyword, "create keyword");
        addCalendarCandidate(candidates, ExpertType.CALENDAR_UPDATE,
                updateKeyword != null ? updateKeyword : updateVerb,
                contextKeyword,
                updateKeyword != null ? "update keyword" : "update verb+context");
        addCalendarCandidate(candidates, ExpertType.CALENDAR_DELETE,
                deleteKeyword != null ? deleteKeyword : deleteVerb,
                contextKeyword,
                deleteKeyword != null ? "delete keyword" : "delete verb+context");
        if (queryKeyword != null) {
            candidates.add(new MatchedExpert(
                    ExpertType.CALENDAR_QUERY,
                    queryKeyword.position,
                    "query keyword",
                    queryKeyword.keyword
            ));
        } else if (createKeyword == null && updateKeyword == null && deleteKeyword == null
                && updateVerb == null && deleteVerb == null && contextKeyword != null) {
            candidates.add(new MatchedExpert(
                    ExpertType.CALENDAR_QUERY,
                    contextKeyword.position,
                    "calendar context fallback",
                    contextKeyword.keyword
            ));
        }

        if (candidates.isEmpty()) {
            traceLines.add("calendar selected -> none");
            return new CalendarSelection(null, traceLines);
        }

        candidates.sort(Comparator.comparingInt(match -> match.position));
        MatchedExpert selected = candidates.get(0);
        traceLines.add("calendar selected -> " + selected.expertType.name()
                + " @ " + selected.position
                + " [" + selected.reason + ": " + selected.keyword + "]");
        return new CalendarSelection(selected, traceLines);
    }

    private KeywordMatch firstWebKeywordMatch(String normalized) {
        KeywordMatch best = null;
        for (String keyword : WEB_KEYWORDS) {
            String normalizedKeyword = normalize(keyword);
            int searchFrom = 0;
            while (searchFrom >= 0 && searchFrom < normalized.length()) {
                int index = normalized.indexOf(normalizedKeyword, searchFrom);
                if (index < 0) {
                    break;
                }
                if (!isCalendarScopedWebKeyword(normalized, keyword, index)) {
                    if (best == null || index < best.position) {
                        best = new KeywordMatch(keyword, index);
                    }
                    break;
                }
                searchFrom = index + normalizedKeyword.length();
            }
        }
        return best;
    }

    private void addCalendarCandidate(List<MatchedExpert> candidates,
                                      ExpertType expertType,
                                      KeywordMatch primaryMatch,
                                      KeywordMatch contextMatch,
                                      String reason) {
        if (primaryMatch == null) {
            return;
        }
        int position = contextMatch == null
                ? primaryMatch.position
                : Math.min(primaryMatch.position, contextMatch.position);
        candidates.add(new MatchedExpert(expertType, position, reason, primaryMatch.keyword));
    }

    private KeywordMatch firstCalendarVerbMatch(String normalized, String[] verbs) {
        KeywordMatch verbMatch = firstKeywordMatch(normalized, verbs);
        KeywordMatch contextMatch = firstKeywordMatch(normalized, CALENDAR_CONTEXT_KEYWORDS);
        if (verbMatch == null || contextMatch == null) {
            return null;
        }
        int position = Math.min(verbMatch.position, contextMatch.position);
        return new KeywordMatch(verbMatch.keyword, position);
    }

    private KeywordMatch firstKeywordMatch(String normalized, String[] keywords) {
        KeywordMatch best = null;
        for (String keyword : keywords) {
            String normalizedKeyword = normalize(keyword);
            int index = normalized.indexOf(normalizedKeyword);
            if (index >= 0 && (best == null || index < best.position)) {
                best = new KeywordMatch(keyword, index);
            }
        }
        return best;
    }

    private boolean isCalendarScopedWebKeyword(String normalized, String keyword, int keywordPosition) {
        if (!"検索".equals(keyword) && !"調べて".equals(keyword)) {
            return false;
        }
        KeywordMatch contextKeyword = firstKeywordMatch(normalized, CALENDAR_CONTEXT_KEYWORDS);
        if (contextKeyword == null) {
            return false;
        }
        int distance = keywordPosition - contextKeyword.position;
        return distance >= 0 && distance <= 6;
    }

    private String formatCalendarTraceLine(String label, KeywordMatch match) {
        if (match == null) {
            return label + " -> no match";
        }
        return label + " -> pos=" + match.position + ", keyword=\"" + match.keyword + "\"";
    }

    private String buildEmptyDebugText(String userInput, String normalized) {
        List<String> traceLines = Collections.singletonList("normalized input is empty");
        return buildDebugText(userInput, normalized, traceLines, Collections.emptyList());
    }

    private String buildDebugText(String userInput,
                                  String normalized,
                                  List<String> traceLines,
                                  List<String> orderedDetails) {
        StringBuilder sb = new StringBuilder();
        sb.append("input: ").append(userInput == null ? "" : userInput).append("\n");
        sb.append("normalized: ").append(normalized).append("\n");
        sb.append("matches:\n");
        if (traceLines == null || traceLines.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (String traceLine : traceLines) {
                sb.append("- ").append(traceLine).append("\n");
            }
        }
        sb.append("ordered steps:\n");
        if (orderedDetails == null || orderedDetails.isEmpty()) {
            sb.append("- (none)");
        } else {
            for (String detail : orderedDetails) {
                sb.append("- ").append(detail).append("\n");
            }
            if (sb.charAt(sb.length() - 1) == '\n') {
                sb.setLength(sb.length() - 1);
            }
        }
        return sb.toString();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .toLowerCase(Locale.ROOT)
                .replace('\u3000', ' ')
                .replaceAll("\\s+", "");
    }

    public static final class SelectionResult {
        private final List<ExpertType> orderedExpertTypes;
        private final String debugText;

        private SelectionResult(List<ExpertType> orderedExpertTypes, String debugText) {
            this.orderedExpertTypes = orderedExpertTypes == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(orderedExpertTypes));
            this.debugText = debugText == null ? "" : debugText;
        }

        public List<ExpertType> getOrderedExpertTypes() {
            return orderedExpertTypes;
        }

        public String getDebugText() {
            return debugText;
        }
    }

    private static final class CalendarSelection {
        private final MatchedExpert matchedExpert;
        private final List<String> traceLines;

        private CalendarSelection(MatchedExpert matchedExpert, List<String> traceLines) {
            this.matchedExpert = matchedExpert;
            this.traceLines = traceLines == null ? Collections.emptyList() : traceLines;
        }

        private MatchedExpert getMatchedExpert() {
            return matchedExpert;
        }

        private List<String> getTraceLines() {
            return traceLines;
        }
    }

    private static final class KeywordMatch {
        private final String keyword;
        private final int position;

        private KeywordMatch(String keyword, int position) {
            this.keyword = keyword;
            this.position = position;
        }
    }

    private static final class MatchedExpert {
        private final ExpertType expertType;
        private final int position;
        private final String reason;
        private final String keyword;

        private MatchedExpert(ExpertType expertType, int position, String reason, String keyword) {
            this.expertType = expertType;
            this.position = position;
            this.reason = reason;
            this.keyword = keyword;
        }
    }
}
