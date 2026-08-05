package com.micklab.llamachat.calendar;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CalendarActionJsonTest {
    @Test
    public void calendarAdditionalStoresTargetQuery() {
        CalendarAdditional additional = new CalendarAdditional(
                "会議を明日の10時に変更して",
                "会議",
                null
        );

        assertEquals("会議", additional.getTargetQuery());
    }

    @Test
    public void withRawTextFallbackKeepsTargetQuery() {
        CalendarActionJson action = new CalendarActionJson(
                CalendarActionType.UPDATE,
                null,
                "2026-05-25T10:00:00+09:00",
                "2026-05-25T11:00:00+09:00",
                null,
                new CalendarAdditional("", "会議", null)
        );

        CalendarActionJson resolved = action.withRawTextFallback("会議を明日の10時に変更して");

        assertEquals("会議", resolved.getAdditional().getTargetQuery());
        assertEquals("会議を明日の10時に変更して", resolved.getAdditional().getRawText());
    }

    @Test
    public void normalizeSearchKeywordRemovesRelativeDatePhrases() {
        assertEquals("会議", CalendarViewModel.normalizeSearchKeyword("今日の会議"));
        assertEquals("定例", CalendarViewModel.normalizeSearchKeyword("来週の定例"));
    }
}
