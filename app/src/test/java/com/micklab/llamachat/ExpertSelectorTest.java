package com.micklab.llamachat;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class ExpertSelectorTest {
    private final ExpertSelector selector = new ExpertSelector();

    @Test
    public void selectAllReturnsWebForSearchKeyword() {
        assertEquals(
                Collections.singletonList(ExpertType.WEB),
                selector.selectAll("天気を検索して", true, true)
        );
    }

    @Test
    public void selectAllReturnsMemorySaveForSaveKeyword() {
        assertEquals(
                Collections.singletonList(ExpertType.MEMORY_SAVE),
                selector.selectAll("これを覚えておいて", true, true)
        );
    }

    @Test
    public void selectAllReturnsMemoryRecallForRecallKeyword() {
        assertEquals(
                Collections.singletonList(ExpertType.MEMORY_RECALL),
                selector.selectAll("さっきの話を思い出して", true, true)
        );
    }

    @Test
    public void memoryKeywordTakesPriorityOverWeb() {
        assertEquals(
                Collections.singletonList(ExpertType.MEMORY_SAVE),
                selector.selectAll("調べたことを覚えておいて", true, true)
        );
    }

    @Test
    public void selectAllIgnoresMemoryKeywordWhenMemoryDisabled() {
        assertEquals(
                Collections.emptyList(),
                selector.selectAll("これを覚えておいて", true, false)
        );
    }

    @Test
    public void selectAllIgnoresWebKeywordWhenWebDisabled() {
        assertEquals(
                Collections.emptyList(),
                selector.selectAll("天気を検索して", false, true)
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
