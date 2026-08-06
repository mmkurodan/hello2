package com.micklab.llamachat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

public final class MemoryRepository {
    private final MemoryDatabase db;

    public MemoryRepository(Context ctx) {
        this.db = MemoryDatabase.get(ctx);
    }

    /** 記憶を保存する。成功したらIDを返す（-1は失敗）。createdAtが0以下なら現在時刻を用いる。 */
    public long save(MemoryRecord r) {
        if (r == null) return -1;
        ContentValues v = new ContentValues();
        v.put(MemoryDatabase.COL_CATEGORY, MemoryRecord.normalizeCategory(r.category));
        v.put(MemoryDatabase.COL_CONTENT, r.content != null ? r.content : "");
        v.put(MemoryDatabase.COL_CREATED_AT,
                r.createdAt > 0 ? r.createdAt : System.currentTimeMillis());
        putNullableInt(v, MemoryDatabase.COL_PLAN_YEAR, r.planYear);
        putNullableInt(v, MemoryDatabase.COL_PLAN_MONTH, r.planMonth);
        putNullableInt(v, MemoryDatabase.COL_PLAN_DAY, r.planDay);
        putNullableInt(v, MemoryDatabase.COL_PLAN_HOUR, r.planHour);
        putNullableInt(v, MemoryDatabase.COL_PLAN_MINUTE, r.planMinute);
        v.put(MemoryDatabase.COL_LOCATION, r.location != null ? r.location : "");
        v.put(MemoryDatabase.COL_COMPLETED, r.completed ? 1 : 0);
        v.put(MemoryDatabase.COL_TAGS, r.tags != null ? r.tags : "");
        return db.getWritableDatabase().insert(MemoryDatabase.TABLE, null, v);
    }

    /** 既存レコードを全項目更新する。成功件数>0でtrue。 */
    public boolean update(MemoryRecord r) {
        if (r == null || r.id <= 0) return false;
        ContentValues v = new ContentValues();
        v.put(MemoryDatabase.COL_CATEGORY, MemoryRecord.normalizeCategory(r.category));
        v.put(MemoryDatabase.COL_CONTENT, r.content != null ? r.content : "");
        putNullableInt(v, MemoryDatabase.COL_PLAN_YEAR, r.planYear);
        putNullableInt(v, MemoryDatabase.COL_PLAN_MONTH, r.planMonth);
        putNullableInt(v, MemoryDatabase.COL_PLAN_DAY, r.planDay);
        putNullableInt(v, MemoryDatabase.COL_PLAN_HOUR, r.planHour);
        putNullableInt(v, MemoryDatabase.COL_PLAN_MINUTE, r.planMinute);
        v.put(MemoryDatabase.COL_LOCATION, r.location != null ? r.location : "");
        v.put(MemoryDatabase.COL_COMPLETED, r.completed ? 1 : 0);
        v.put(MemoryDatabase.COL_TAGS, r.tags != null ? r.tags : "");
        int n = db.getWritableDatabase().update(MemoryDatabase.TABLE, v,
                MemoryDatabase.COL_ID + "=?", new String[]{String.valueOf(r.id)});
        return n > 0;
    }

    /** ToDo の完了状態のみ更新する。 */
    public boolean setCompleted(long id, boolean completed) {
        if (id <= 0) return false;
        ContentValues v = new ContentValues();
        v.put(MemoryDatabase.COL_COMPLETED, completed ? 1 : 0);
        int n = db.getWritableDatabase().update(MemoryDatabase.TABLE, v,
                MemoryDatabase.COL_ID + "=?", new String[]{String.valueOf(id)});
        return n > 0;
    }

    public boolean delete(long id) {
        if (id <= 0) return false;
        int n = db.getWritableDatabase().delete(MemoryDatabase.TABLE,
                MemoryDatabase.COL_ID + "=?", new String[]{String.valueOf(id)});
        return n > 0;
    }

    /** content / location / tags に対してLIKE検索。空クエリの場合は最新10件を返す。 */
    public List<MemoryRecord> search(String query) {
        if (query == null || query.trim().isEmpty()) return getRecent(10);
        List<MemoryRecord> results = new ArrayList<>();
        String like = "%" + query.trim() + "%";
        try (Cursor c = db.getReadableDatabase().query(
                MemoryDatabase.TABLE, null,
                MemoryDatabase.COL_CONTENT + " LIKE ? OR "
                        + MemoryDatabase.COL_LOCATION + " LIKE ? OR "
                        + MemoryDatabase.COL_TAGS + " LIKE ?",
                new String[]{like, like, like},
                null, null,
                MemoryDatabase.COL_CREATED_AT + " DESC", "20")) {
            while (c.moveToNext()) results.add(fromCursor(c));
        }
        return results;
    }

    /** 最新のlimit件を返す。 */
    public List<MemoryRecord> getRecent(int limit) {
        List<MemoryRecord> results = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().query(
                MemoryDatabase.TABLE, null, null, null, null, null,
                MemoryDatabase.COL_CREATED_AT + " DESC",
                String.valueOf(Math.max(1, limit)))) {
            while (c.moveToNext()) results.add(fromCursor(c));
        }
        return results;
    }

    /** 指定分類のレコードをすべて返す（通知の予定/期限判定用）。 */
    public List<MemoryRecord> getByCategory(String category) {
        List<MemoryRecord> results = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().query(
                MemoryDatabase.TABLE, null,
                MemoryDatabase.COL_CATEGORY + "=?",
                new String[]{MemoryRecord.normalizeCategory(category)},
                null, null,
                MemoryDatabase.COL_CREATED_AT + " DESC")) {
            while (c.moveToNext()) results.add(fromCursor(c));
        }
        return results;
    }

    /** 全記憶件数を返す。 */
    public int count() {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + MemoryDatabase.TABLE, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private static void putNullableInt(ContentValues v, String col, Integer value) {
        if (value == null) v.putNull(col);
        else v.put(col, value);
    }

    private static Integer getNullableInt(Cursor c, String col) {
        int idx = c.getColumnIndexOrThrow(col);
        return c.isNull(idx) ? null : c.getInt(idx);
    }

    private MemoryRecord fromCursor(Cursor c) {
        return new MemoryRecord(
                c.getLong(c.getColumnIndexOrThrow(MemoryDatabase.COL_ID)),
                c.getString(c.getColumnIndexOrThrow(MemoryDatabase.COL_CATEGORY)),
                c.getString(c.getColumnIndexOrThrow(MemoryDatabase.COL_CONTENT)),
                c.getLong(c.getColumnIndexOrThrow(MemoryDatabase.COL_CREATED_AT)),
                getNullableInt(c, MemoryDatabase.COL_PLAN_YEAR),
                getNullableInt(c, MemoryDatabase.COL_PLAN_MONTH),
                getNullableInt(c, MemoryDatabase.COL_PLAN_DAY),
                getNullableInt(c, MemoryDatabase.COL_PLAN_HOUR),
                getNullableInt(c, MemoryDatabase.COL_PLAN_MINUTE),
                c.getString(c.getColumnIndexOrThrow(MemoryDatabase.COL_LOCATION)),
                c.getInt(c.getColumnIndexOrThrow(MemoryDatabase.COL_COMPLETED)) != 0,
                c.getString(c.getColumnIndexOrThrow(MemoryDatabase.COL_TAGS)));
    }
}
