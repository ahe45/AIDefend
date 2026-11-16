package com.glassesscanner;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class HistoryScreen {
    private final ScannerActivity activity;
    private final ScanHistory history;
    private final LinearLayout rows;
    private int revision;

    HistoryScreen(ScannerActivity activity) {
        this.activity = activity;
        history = ScanHistory.get(activity);
        rows = activity.findViewById(R.id.history_rows);
    }

    void refresh() {
        int requested = ++revision;
        history.list(entries -> {
            if (requested != revision || activity.isDestroyed()) return;
            rows.removeAllViews();
            if (entries == null || entries.isEmpty()) {
                rows.addView(ScanScreen.label(activity, entries == null
                        ? "이력을 읽을 수 없습니다." : "저장된 관측 이력이 없습니다."));
                return;
            }
            for (ScanHistory.Entry entry : entries) {
                MaterialButton button = button(entry.title() + "\n" + entry.summary());
                button.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                button.setOnClickListener(view -> open(entry.id()));
                rows.addView(button);
            }
            MaterialButton clear = button("전체 이력 삭제");
            clear.setOnClickListener(view -> confirmDelete(null));
            rows.addView(clear);
        });
    }

    void open(String id) {
        history.detail(id, detail -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (detail == null) {
                new IconDialogBuilder(activity).setTitle(R.string.history)
                        .setMessage("이 기록을 찾을 수 없습니다.")
                        .setPositiveButton("닫기", null).show();
                return;
            }
            ScrollView scroll = new ScrollView(activity);
            int padding = activity.getResources().getDimensionPixelSize(R.dimen.space_medium);
            scroll.setPadding(padding, 0, padding, padding);
            scroll.addView(ScanScreen.label(activity, detail.entry().summary() + "\n\n" + detail.observations()));
            new IconDialogBuilder(activity).setTitle(detail.entry().title()).setView(scroll)
                    .setPositiveButton("닫기", null)
                    .setNeutralButton("삭제", (dialog, which) -> confirmDelete(id)).show();
        });
    }

    private void confirmDelete(String id) {
        new IconDialogBuilder(activity)
                .setTitle(id == null ? "전체 이력을 삭제할까요?" : "이 기록을 삭제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> history.delete(id, saved -> {
                    if (!activity.isDestroyed()) {
                        if (Boolean.TRUE.equals(saved)) refresh();
                        else error("이력을 삭제하지 못했습니다.");
                    }
                })).show();
    }

    void chooseRetention(ScanPreferences preferences) {
        int[] limits = {10, 30, 60};
        String[] labels = {"최근 10회", "최근 30회", "최근 60회"};
        int selected = preferences.retention() == 10 ? 0 : preferences.retention() == 60 ? 2 : 1;
        new IconDialogBuilder(activity).setTitle("이력 보관 범위")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    dialog.dismiss();
                    if (limits[which] < preferences.retention()) {
                        new IconDialogBuilder(activity).setTitle("보관 범위를 줄일까요?")
                                .setMessage("선택한 범위를 벗어난 이전 기록은 삭제됩니다.")
                                .setNegativeButton("취소", null)
                                .setPositiveButton("적용", (confirm, button) -> applyRetention(preferences, limits[which])).show();
                    } else applyRetention(preferences, limits[which]);
                }).setNegativeButton("취소", null).show();
    }

    private void applyRetention(ScanPreferences preferences, int limit) {
        history.trim(limit, saved -> {
            if (Boolean.TRUE.equals(saved)) {
                preferences.retention(limit);
                if (!activity.isDestroyed()) activity.preferencesChanged();
            } else if (!activity.isDestroyed()) error("보관 범위를 적용하지 못했습니다.");
        });
    }

    private void error(String text) {
        new IconDialogBuilder(activity).setMessage(text).setPositiveButton("닫기", null).show();
    }

    private MaterialButton button(String label) {
        MaterialButton button = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        button.setMinHeight(activity.getResources().getDimensionPixelSize(R.dimen.touch_min));
        button.setText(label);
        return button;
    }
}
