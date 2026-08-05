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

    public ExpertType select(String userInput, boolean webAvailable, boolean calendarAvailable, boolean memoryAvailable) {
        List<ExpertType> ordered = selectAll(userInput, webAvailable, calendarAvailable, memoryAvailable);
        return ordered.isEmpty() ? ExpertType.NONE : ordered.get(0);
    }

    public List<ExpertType> selectAll(String userInput, boolean webAvailable, boolean calendarAvailable, boolean memoryAvailable) {
        return selectDetailed(userInput, webAvailable, calendarAvailable, memoryAvailable).getOrderedExpertTypes();
    }

    public SelectionResult selectDetailed(String userInput, boolean webAvailable, boolean calendarAvailable, boolean memoryAvailable) {
        String normalized = normalize(userInput);
        if (normalized.isEmpty()) {
            return new SelectionResult(Collections.emptyList(), buildEmptyDebugText(userInput, normalized));
        }

        // 記憶キーワードは有効時のみ検出
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

    private KeywordMatch firstWebKeywordMatch(String normalized) {
        return firstKeywordMatch(normalized, WEB_KEYWORDS);
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
        if (value == null) return "";
        return value
                .toLowerCase(Locale.ROOT)
                .replace('　', ' ')
                .replaceAll("\\s+", "");
    }

    private String buildEmptyDebugText(String userInput, String normalized) {
        return buildDebugText(userInput, normalized,
                Collections.singletonList("normalized input is empty"),
                Collections.emptyList());
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
