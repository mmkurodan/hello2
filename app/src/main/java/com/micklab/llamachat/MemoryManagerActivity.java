package com.micklab.llamachat;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
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
        String seed = etNewMemory == null ? "" : etNewMemory.getText().toString().trim();
        showEditor(null, seed);
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

        // 分類・予定・登録日時のヘッダ
        StringBuilder header = new StringBuilder();
        header.append("【").append(categoryLabel(record.category)).append("】");
        String sched = record.scheduleLabel(appLanguage);
        if (!sched.isEmpty()) header.append(" ").append(sched);
        if (!record.location.isEmpty()) header.append(" @").append(record.location);
        if (record.isTodo()) {
            header.append(record.completed
                    ? t("  [done]", "  [完了]") : t("  [open]", "  [未完了]"));
        }
        TextView tvHeader = new TextView(this);
        tvHeader.setText(header.toString());
        tvHeader.setTextSize(12f);
        tvHeader.setTextColor(record.isTodo() && !record.completed ? 0xFFB00020 : 0xFF3366AA);
        row.addView(tvHeader);

        TextView tvDate = new TextView(this);
        tvDate.setText(t("saved ", "登録 ") + DATE_FMT.format(new Date(record.createdAt)));
        tvDate.setTextSize(10f);
        tvDate.setTextColor(0xFFAAAAAA);
        row.addView(tvDate);

        TextView tvContent = new TextView(this);
        tvContent.setText(record.content);
        tvContent.setTextSize(14f);
        tvContent.setTextColor(0xFF222222);
        tvContent.setPadding(0, 4, 0, 6);
        tvContent.setTextIsSelectable(true);
        row.addView(tvContent);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        if (record.isTodo()) {
            Button btnDone = new Button(this);
            btnDone.setText(record.completed
                    ? t("Reopen", "未完了に") : t("Done", "完了に"));
            btnDone.setTextSize(12f);
            LinearLayout.LayoutParams lpD = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpD.setMargins(0, 0, 8, 0);
            btnDone.setLayoutParams(lpD);
            btnDone.setOnClickListener(v -> {
                memoryRepository.setCompleted(record.id, !record.completed);
                refreshList();
            });
            btnRow.addView(btnDone);
        }

        Button btnEdit = new Button(this);
        btnEdit.setText(t("Edit", "編集"));
        btnEdit.setTextSize(12f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 8, 0);
        btnEdit.setLayoutParams(lp);
        btnEdit.setOnClickListener(v -> showEditor(record, null));
        btnRow.addView(btnEdit);

        Button btnDelete = new Button(this);
        btnDelete.setText(t("Delete", "削除"));
        btnDelete.setTextSize(12f);
        btnDelete.setOnClickListener(v -> showDeleteDialog(record));
        btnRow.addView(btnDelete);

        row.addView(btnRow);
        return row;
    }

    /** 追加（existing==null, seedを内容に）／編集の共通フォーム。 */
    private void showEditor(MemoryRecord existing, String seedContent) {
        boolean adding = existing == null;
        int pad = dp(16);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, pad);

        Spinner spCat = new Spinner(this);
        spCat.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{categoryLabel(MemoryRecord.CAT_MEMO),
                        categoryLabel(MemoryRecord.CAT_TODO),
                        categoryLabel(MemoryRecord.CAT_PLAN)}));
        spCat.setSelection(adding ? 0 : catIndex(existing.category));
        form.addView(label(t("Category", "分類")));
        form.addView(spCat);

        EditText etContent = new EditText(this);
        etContent.setMinLines(2);
        etContent.setText(adding ? (seedContent == null ? "" : seedContent) : existing.content);
        form.addView(label(t("Content", "内容")));
        form.addView(etContent);

        form.addView(label(t("Plan (blank = every year/month/day or all-day)",
                "計画日時（空欄=毎年/毎月/毎日・終日）")));
        EditText etY = numField(adding ? null : existing.planYear, t("YYYY", "年"));
        EditText etMo = numField(adding ? null : existing.planMonth, t("MM", "月"));
        EditText etD = numField(adding ? null : existing.planDay, t("DD", "日"));
        EditText etH = numField(adding ? null : existing.planHour, t("HH", "時"));
        EditText etMi = numField(adding ? null : existing.planMinute, t("mm", "分"));
        form.addView(hRow(etY, etMo, etD));
        form.addView(hRow(etH, etMi));

        EditText etLoc = new EditText(this);
        etLoc.setSingleLine(true);
        etLoc.setText(adding ? "" : existing.location);
        form.addView(label(t("Location", "場所")));
        form.addView(etLoc);

        CheckBox cbDone = new CheckBox(this);
        cbDone.setText(t("Completed (ToDo only)", "完了（ToDoのみ）"));
        cbDone.setChecked(!adding && existing.completed);
        form.addView(cbDone);

        ScrollView sv = new ScrollView(this);
        sv.addView(form);

        new AlertDialog.Builder(this)
                .setTitle(adding ? t("Add Memory", "記録を追加") : t("Edit Memory", "記録を編集"))
                .setView(sv)
                .setPositiveButton(t("Save", "保存"), (dialog, which) -> {
                    String content = etContent.getText().toString().trim();
                    if (content.isEmpty()) {
                        toast(t("Content cannot be empty.", "内容を入力してください。"));
                        return;
                    }
                    String cat = categoryFromIndex(spCat.getSelectedItemPosition());
                    Integer y = parseNum(etY), mo = parseNum(etMo), da = parseNum(etD);
                    Integer h = parseNum(etH), mi = parseNum(etMi);

                    // 分類ごとの制約を適用する（保存経路と同一ルール）。
                    if (MemoryRecord.CAT_MEMO.equals(cat)) {
                        y = mo = da = h = mi = null;
                        etLoc.setText("");
                    } else if (MemoryRecord.CAT_TODO.equals(cat)) {
                        LocalDate today = LocalDate.now();
                        if (y == null) y = today.getYear();
                        if (mo == null) mo = today.getMonthValue();
                        if (da == null) da = today.getDayOfMonth();
                    }
                    if (h == null || mi == null) { h = null; mi = null; }

                    boolean completed = MemoryRecord.CAT_TODO.equals(cat) && cbDone.isChecked();
                    long created = adding ? System.currentTimeMillis() : existing.createdAt;
                    long id = adding ? 0 : existing.id;
                    String tags = adding ? null : existing.tags;
                    MemoryRecord rec = new MemoryRecord(id, cat, content, created,
                            y, mo, da, h, mi, etLoc.getText().toString().trim(), completed, tags);

                    boolean ok = adding ? memoryRepository.save(rec) >= 0
                            : memoryRepository.update(rec);
                    if (ok && adding && etNewMemory != null) etNewMemory.setText("");
                    refreshList();
                    toast(ok ? t("Saved.", "保存しました。") : t("Failed.", "失敗しました。"));
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
                    memoryRepository.delete(record.id);
                    refreshList();
                    toast(t("Deleted.", "削除しました。"));
                })
                .setNegativeButton(t("Cancel", "キャンセル"), null)
                .show();
    }

    // ===== フォーム部品 =====

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(0xFF666666);
        tv.setPadding(0, dp(8), 0, 0);
        return tv;
    }

    private EditText numField(Integer value, String hint) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint(hint);
        et.setSingleLine(true);
        if (value != null) et.setText(String.valueOf(value));
        return et;
    }

    private LinearLayout hRow(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, dp(6), 0);
            v.setLayoutParams(lp);
            r.addView(v);
        }
        return r;
    }

    private Integer parseNum(EditText et) {
        String s = et.getText().toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int catIndex(String category) {
        if (MemoryRecord.CAT_TODO.equals(category)) return 1;
        if (MemoryRecord.CAT_PLAN.equals(category)) return 2;
        return 0;
    }

    private String categoryFromIndex(int i) {
        if (i == 1) return MemoryRecord.CAT_TODO;
        if (i == 2) return MemoryRecord.CAT_PLAN;
        return MemoryRecord.CAT_MEMO;
    }

    private String categoryLabel(String category) {
        boolean ja = "ja".equals(appLanguage);
        if (MemoryRecord.CAT_TODO.equals(category)) return ja ? "ToDo" : "TODO";
        if (MemoryRecord.CAT_PLAN.equals(category)) return ja ? "予定" : "PLAN";
        return ja ? "メモ" : "MEMO";
    }

    private String t(String en, String ja) {
        return "ja".equals(appLanguage) ? ja : en;
    }
}
