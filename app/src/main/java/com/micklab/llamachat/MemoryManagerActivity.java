package com.micklab.llamachat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MemoryManagerActivity extends Activity {

    private LinearLayout memoryListContainer;
    private EditText etNewMemory;
    private Button btnAddMemory;
    private Button btnMemoryClose;
    private TextView tvMemoryTitle;
    private TextView tvMemoryCount;

    private MemoryRepository memoryRepository;
    private String appLanguage = "en";

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memory_manager);

        appLanguage = getIntent() != null
                ? getIntent().getStringExtra("appLanguage") : "en";
        if (appLanguage == null) appLanguage = "en";

        memoryRepository = new MemoryRepository(this);

        memoryListContainer = findViewById(R.id.memoryListContainer);
        etNewMemory = findViewById(R.id.etNewMemory);
        btnAddMemory = findViewById(R.id.btnAddMemory);
        btnMemoryClose = findViewById(R.id.btnMemoryClose);
        tvMemoryTitle = findViewById(R.id.tvMemoryTitle);
        tvMemoryCount = findViewById(R.id.tvMemoryCount);

        if (tvMemoryTitle != null) {
            tvMemoryTitle.setText(t("Memory Manager", "メモリ管理"));
        }
        if (etNewMemory != null) {
            etNewMemory.setHint(t("New memory to save...", "記録する内容..."));
        }
        if (btnAddMemory != null) {
            btnAddMemory.setText(t("Add", "追加"));
            btnAddMemory.setOnClickListener(v -> onAddClicked());
        }
        if (btnMemoryClose != null) {
            btnMemoryClose.setText(t("Close", "閉じる"));
            btnMemoryClose.setOnClickListener(v -> finish());
        }

        refreshList();
    }

    private void onAddClicked() {
        if (etNewMemory == null) return;
        String content = etNewMemory.getText().toString().trim();
        if (TextUtils.isEmpty(content)) {
            Toast.makeText(this, t("Please enter content.", "内容を入力してください。"), Toast.LENGTH_SHORT).show();
            return;
        }
        memoryRepository.save(content, null);
        etNewMemory.setText("");
        refreshList();
        Toast.makeText(this, t("Saved.", "保存しました。"), Toast.LENGTH_SHORT).show();
    }

    private void refreshList() {
        if (memoryListContainer == null) return;
        memoryListContainer.removeAllViews();

        List<MemoryRecord> records = memoryRepository.getRecent(200);
        int count = records.size();
        if (tvMemoryCount != null) {
            tvMemoryCount.setText(count + t(" items", " 件"));
        }

        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText(t("No memories saved yet.", "記録はまだありません。"));
            empty.setPadding(8, 16, 8, 16);
            empty.setTextColor(0xFF888888);
            memoryListContainer.addView(empty);
            return;
        }

        for (MemoryRecord record : records) {
            memoryListContainer.addView(buildItemView(record));
            View divider = new View(this);
            divider.setBackgroundColor(0xFFEEEEEE);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dp.setMargins(0, 2, 0, 2);
            divider.setLayoutParams(dp);
            memoryListContainer.addView(divider);
        }
    }

    private View buildItemView(MemoryRecord record) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(4, 8, 4, 8);

        // 日時ラベル
        TextView tvDate = new TextView(this);
        tvDate.setText(DATE_FMT.format(new Date(record.createdAt)));
        tvDate.setTextSize(11f);
        tvDate.setTextColor(0xFF888888);
        row.addView(tvDate);

        // 内容テキスト
        TextView tvContent = new TextView(this);
        tvContent.setText(record.content);
        tvContent.setTextSize(14f);
        tvContent.setTextColor(0xFF222222);
        tvContent.setPadding(0, 4, 0, 6);
        tvContent.setTextIsSelectable(true);
        row.addView(tvContent);

        // ボタン行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        Button btnEdit = new Button(this);
        btnEdit.setText(t("Edit", "編集"));
        btnEdit.setTextSize(12f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 8, 0);
        btnEdit.setLayoutParams(lp);
        btnEdit.setOnClickListener(v -> showEditDialog(record));
        btnRow.addView(btnEdit);

        Button btnDelete = new Button(this);
        btnDelete.setText(t("Delete", "削除"));
        btnDelete.setTextSize(12f);
        btnDelete.setOnClickListener(v -> showDeleteDialog(record));
        btnRow.addView(btnDelete);

        row.addView(btnRow);
        return row;
    }

    private void showEditDialog(MemoryRecord record) {
        EditText input = new EditText(this);
        input.setText(record.content);
        input.setSelection(record.content.length());
        input.setMinLines(3);
        input.setPadding(32, 16, 32, 16);

        new AlertDialog.Builder(this)
                .setTitle(t("Edit Memory", "記録を編集"))
                .setView(input)
                .setPositiveButton(t("Save", "保存"), (dialog, which) -> {
                    String newContent = input.getText().toString().trim();
                    if (TextUtils.isEmpty(newContent)) {
                        Toast.makeText(this,
                                t("Content cannot be empty.", "内容を入力してください。"),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // IDで削除→新規追加（SQLiteOpenHelperのUpdateでも可）
                    android.database.sqlite.SQLiteDatabase db =
                            MemoryDatabase.get(this).getWritableDatabase();
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(MemoryDatabase.COL_CONTENT, newContent);
                    db.update(MemoryDatabase.TABLE, cv,
                            MemoryDatabase.COL_ID + "=?",
                            new String[]{String.valueOf(record.id)});
                    refreshList();
                    Toast.makeText(this, t("Updated.", "更新しました。"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(t("Cancel", "キャンセル"), null)
                .show();
    }

    private void showDeleteDialog(MemoryRecord record) {
        String preview = record.content.length() > 40
                ? record.content.substring(0, 40) + "..."
                : record.content;
        new AlertDialog.Builder(this)
                .setTitle(t("Delete Memory", "記録を削除"))
                .setMessage(t("Delete: 「", "削除しますか：「") + preview + "」")
                .setPositiveButton(t("Delete", "削除"), (dialog, which) -> {
                    android.database.sqlite.SQLiteDatabase db =
                            MemoryDatabase.get(this).getWritableDatabase();
                    db.delete(MemoryDatabase.TABLE,
                            MemoryDatabase.COL_ID + "=?",
                            new String[]{String.valueOf(record.id)});
                    refreshList();
                    Toast.makeText(this, t("Deleted.", "削除しました。"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(t("Cancel", "キャンセル"), null)
                .show();
    }

    private String t(String en, String ja) {
        return "ja".equals(appLanguage) ? ja : en;
    }
}
