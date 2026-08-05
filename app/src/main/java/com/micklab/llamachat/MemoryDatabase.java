package com.micklab.llamachat;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public final class MemoryDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "llamachat_memory.db";
    private static final int DB_VERSION = 1;

    static final String TABLE = "memories";
    static final String COL_ID = "id";
    static final String COL_CONTENT = "content";
    static final String COL_TAGS = "tags";
    static final String COL_CREATED_AT = "created_at";

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
                + COL_CONTENT + " TEXT NOT NULL,"
                + COL_TAGS + " TEXT,"
                + COL_CREATED_AT + " INTEGER NOT NULL"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }
}
