package com.micklab.llamachat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class ExpertSelectorTest {
    private final ExpertSelector selector = new ExpertSelector();

    @Test
    public void selectAllReturnsExpertsInAppearanceOrder_webThenCalendar() {
        assertEquals(
                Arrays.asList(ExpertType.WEB, ExpertType.CALENDAR_CREATE),
                selector.selectAll("天気を検索して、そのあと予定を入れて", true, true)
        );
    }

    @Test
    public void selectAllReturnsExpertsInAppearanceOrder_calendarThenWeb() {
        assertEquals(
                Arrays.asList(ExpertType.CALENDAR_DELETE, ExpertType.WEB),
                selector.selectAll("予定を削除してから検索して", true, true)
        );
    }

    @Test
    public void selectAllReturnsCreateWhenCreateKeywordAppearsFirst() {
        assertEquals(
                Collections.singletonList(ExpertType.CALENDAR_CREATE),
                selector.selectAll("明日の予定を入れて", false, true)
        );
    }

    @Test
    public void selectAllReturnsCalendarQueryForScheduleLookup() {
        assertEquals(
                Collections.singletonList(ExpertType.CALENDAR_QUERY),
                selector.selectAll("明日の予定を確認して", false, true)
        );
    }

    @Test
    public void selectAllDoesNotTreatCalendarSearchAsWebSearch() {
        assertEquals(
                Collections.singletonList(ExpertType.CALENDAR_QUERY),
                selector.selectAll("会議の予定を検索して", true, true)
        );
    }

    @Test
    public void selectAllReturnsCalendarUpdateForChangeRequest() {
        assertEquals(
                Collections.singletonList(ExpertType.CALENDAR_UPDATE),
                selector.selectAll("会議の予定を変更して", false, true)
        );
    }

    @Test
    public void selectAllReturnsEmptyWhenNoKeywordMatches() {
        assertEquals(
                Collections.emptyList(),
                selector.selectAll("こんにちは", true, true)
        );
    }
}
