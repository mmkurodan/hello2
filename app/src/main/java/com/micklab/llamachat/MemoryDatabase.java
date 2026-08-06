package com.micklab.llamachat;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public final class MemoryDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "llamachat_memory.db";
    // v2: 分類（Memo/ToDo/Plan）・計画日時・場所・完了状態を追加（マイグレーションせず作り直す）。
    private static final int DB_VERSION = 2;

    static final String TABLE = "memories";
    static final String COL_ID = "id";
    static final String COL_CATEGORY = "category";
    static final String COL_CONTENT = "content";
    static final String COL_CREATED_AT = "created_at";
    static final String COL_PLAN_YEAR = "plan_year";
    static final String COL_PLAN_MONTH = "plan_month";
    static final String COL_PLAN_DAY = "plan_day";
    static final String COL_PLAN_HOUR = "plan_hour";
    static final String COL_PLAN_MINUTE = "plan_minute";
    static final String COL_LOCATION = "location";
    static final String COL_COMPLETED = "completed";
    static final String COL_TAGS = "tags";

    private static volatile MemoryDatabase instance;

    public static MemoryDatabase get(Context ctx) {
        if (instance == null) {
            synchronized (MemoryDatabase.class) {
                if (instance == null) {
                    instance = new MemoryDatabase(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private MemoryDatabase(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COL_CATEGORY + " TEXT NOT NULL,"
                + COL_CONTENT + " TEXT NOT NULL,"
                + COL_CREATED_AT + " INTEGER NOT NULL,"
                + COL_PLAN_YEAR + " INTEGER,"
                + COL_PLAN_MONTH + " INTEGER,"
                + COL_PLAN_DAY + " INTEGER,"
                + COL_PLAN_HOUR + " INTEGER,"
                + COL_PLAN_MINUTE + " INTEGER,"
                + COL_LOCATION + " TEXT,"
                + COL_COMPLETED + " INTEGER NOT NULL DEFAULT 0,"
                + COL_TAGS + " TEXT"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 既存メモリのマイグレーションは行わず作り直す。
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }
}
