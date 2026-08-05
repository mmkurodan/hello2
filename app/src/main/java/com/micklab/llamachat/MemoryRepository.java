package com.micklab.llamachat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

public final class MemoryRepository {
    private final MemoryDatabase db;

    public MemoryRepository(Context ctx) {
        this.db = MemoryDatabase.get(ctx);
    }

    /** 記憶を保存する。成功したらIDを返す（-1は失敗）。 */
    public long save(String content, String tags) {
        ContentValues values = new ContentValues();
        values.put(MemoryDatabase.COL_CONTENT, content != null ? content : "");
        values.put(MemoryDatabase.COL_TAGS, tags != null ? tags : "");
        values.put(MemoryDatabase.COL_CREATED_AT, System.currentTimeMillis());
        return db.getWritableDatabase().insert(MemoryDatabase.TABLE, null, values);
    }

    /** content/tagsに対してLIKE検索。空クエリの場合は最新10件を返す。 */
    public List<MemoryRecord> search(String query) {
        if (query == null || query.trim().isEmpty()) return getRecent(10);
        List<MemoryRecord> results = new ArrayList<>();
        String like = "%" + query.trim() + "%";
        try (Cursor cursor = db.getReadableDatabase().query(
                MemoryDatabase.TABLE, null,
                MemoryDatabase.COL_CONTENT + " LIKE ? OR " + MemoryDatabase.COL_TAGS + " LIKE ?",
                new String[]{like, like},
                null, null,
                MemoryDatabase.COL_CREATED_AT + " DESC", "20")) {
            while (cursor.moveToNext()) {
                results.add(fromCursor(cursor));
            }
        }
        return results;
    }

    /** 最新のlimit件を返す。 */
    public List<MemoryRecord> getRecent(int limit) {
        List<MemoryRecord> results = new ArrayList<>();
        try (Cursor cursor = db.getReadableDatabase().query(
                MemoryDatabase.TABLE, null, null, null, null, null,
                MemoryDatabase.COL_CREATED_AT + " DESC",
                String.valueOf(Math.max(1, limit)))) {
            while (cursor.moveToNext()) {
                results.add(fromCursor(cursor));
            }
        }
        return results;
    }

    /** 全記憶件数を返す。 */
    public int count() {
        try (Cursor cursor = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + MemoryDatabase.TABLE, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private MemoryRecord fromCursor(Cursor c) {
        return new MemoryRecord(
                c.getLong(c.getColumnIndexOrThrow(MemoryDatabase.COL_ID)),
                c.getString(c.getColumnIndexOrThrow(MemoryDatabase.COL_CONTENT)),
                c.getString(c.getColumnIndexOrThrow(MemoryDatabase.COL_TAGS)),
                c.getLong(c.getColumnIndexOrThrow(MemoryDatabase.COL_CREATED_AT)));
    }
}
