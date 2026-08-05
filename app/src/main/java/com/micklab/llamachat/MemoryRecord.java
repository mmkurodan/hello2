package com.micklab.llamachat;

public final class MemoryRecord {
    public final long id;
    public final String content;
    public final String tags;
    public final long createdAt;

    public MemoryRecord(long id, String content, String tags, long createdAt) {
        this.id = id;
        this.content = content != null ? content : "";
        this.tags = tags != null ? tags : "";
        this.createdAt = createdAt;
    }
}
