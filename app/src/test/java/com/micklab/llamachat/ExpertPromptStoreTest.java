package com.micklab.llamachat;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ExpertPromptStoreTest {
    @Test
    public void queryPromptMentionsDateExpressionsMustNotGoInTitle() {
        String prompt = ExpertPromptStore.getPromptSpec(
                ExpertPromptStore.KEY_CALENDAR_QUERY_JUDGE
        ).getDefaultValue();

        assertTrue(prompt.contains("日付表現は title に入れず"));
    }

    @Test
    public void updatePromptMentionsDateExpressionsMustNotGoInTargetQuery() {
        String prompt = ExpertPromptStore.getPromptSpec(
                ExpertPromptStore.KEY_CALENDAR_UPDATE_JUDGE
        ).getDefaultValue();

        assertTrue(prompt.contains("日付表現は title / targetQuery に入れない"));
    }
}
