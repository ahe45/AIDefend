package com.uplusys.deviceguard;

import android.view.View;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.DateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

final class HistoryScreen {
    private final ScannerActivity activity;
    private final ScanHistory history;
    private final LinearLayout rows;
    private int revision;
    private View openPage;
    private String openId;
    private boolean candidatesOnly = true;
    private int selectedResultPosition = -1;

    HistoryScreen(ScannerActivity activity) {
        this(activity, ScanHistory.get(activity));
    }

    HistoryScreen(ScannerActivity activity, ScanHistory history) {
        this.activity = activity;
        this.history = history;
        rows = activity.findViewById(R.id.history_rows);
    }

    void refresh() {
        int requested = ++revision;
        history.list(entries -> {
            if (requested != revision || activity.isDestroyed()) return;
            rows.removeAllViews();
            if (entries == null || entries.isEmpty()) {
                rows.addView(ScanScreen.label(activity, entries == null
                        ? "이력을 읽을 수 없습니다." : "저장된 스캔 이력이 없습니다."));
                return;
            }
            for (ScanHistory.Entry entry : entries) {
                rows.addView(sessionCard(entry));
            }
            MaterialButton clear = button("전체 이력 삭제");
            clear.setOnClickListener(view -> confirmDelete(null));
            rows.addView(clear);
        });
    }

    void open(String id) {
        open(id, true, -1);
    }

    void open(String id, boolean onlyCandidates, int restorePosition) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        View body = LayoutInflater.from(activity).inflate(R.layout.screen_history_session, null);
        TextView counts = body.findViewById(R.id.history_session_counts);
        TextView notice = body.findViewById(R.id.history_session_notice);
        TextView empty = body.findViewById(R.id.history_session_empty);
        View emptyScroll = body.findViewById(R.id.history_session_empty_scroll);
        RecyclerView list = body.findViewById(R.id.history_session_results);
        list.setLayoutManager(new LinearLayoutManager(activity));
        var adapter = new HistoryResults(this::openResult);
        list.setAdapter(adapter);
        MaterialSwitch filter = body.findViewById(R.id.history_candidates_only);
        filter.setChecked(onlyCandidates);
        filter.setEnabled(false);
        var delete = activity.settingsAction(R.id.settings_page_delete, "이력 삭제", R.drawable.ic_close, false);
        delete.setEnabled(false);
        delete.setOnClickListener(view -> confirmDelete(id));
        empty.setText("스캔 이력을 불러오는 중입니다.");
        View page = activity.settingsWithActions(body, delete);
        openPage = page;
        openId = id;
        candidatesOnly = onlyCandidates;
        activity.showSettingsPage(R.id.history_session_results, "스캔 이력 상세", page, () -> {
            if (openPage == page) {
                openPage = null;
                openId = null;
                selectedResultPosition = -1;
            }
        });
        history.detail(id, detail -> {
            if (openPage != page || activity.isFinishing() || activity.isDestroyed()) return;
            if (detail == null) {
                empty.setText("이 기록을 찾을 수 없습니다.");
                return;
            }
            ScanHistory.Entry entry = detail.entry();
            counts.setText(sessionCounts(entry));
            notice.setText(entry.fullResults() ? "" : "이전 이력은 저장된 항목만 표시합니다.");
            notice.setVisibility(entry.fullResults() ? View.GONE : View.VISIBLE);
            List<ScanHistory.Result> sorted = detail.results().stream().sorted(
                    Comparator.comparing(ScanHistory.Result::candidate).reversed()
                            .thenComparing(ScanHistory.Result::rssi, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparingInt(ScanHistory.Result::position)).collect(java.util.stream.Collectors.toList());
            Runnable refreshResults = () -> {
                List<ScanHistory.Result> visible = candidatesOnly
                        ? sorted.stream().filter(ScanHistory.Result::candidate).collect(java.util.stream.Collectors.toList())
                        : sorted;
                adapter.show(entry, visible);
                list.scrollToPosition(0);
                emptyScroll.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
                if (visible.isEmpty()) {
                    if (!candidatesOnly && entry.legacyBody() != null && !detail.observations().isBlank()) {
                        empty.setText(detail.observations());
                    } else if (candidatesOnly && (!sorted.isEmpty() || entry.legacyBody() != null)) {
                        empty.setText("저장된 의심 기기가 없습니다. 전체 기록은 필터를 끄면 볼 수 있습니다.");
                    } else empty.setText(candidatesOnly ? "저장된 의심 기기가 없습니다." : "저장된 스캔 기기가 없습니다.");
                }
            };
            filter.setOnCheckedChangeListener((view, checked) -> {
                candidatesOnly = checked;
                refreshResults.run();
            });
            filter.setEnabled(true);
            delete.setEnabled(true);
            refreshResults.run();
            if (restorePosition >= 0) {
                sorted.stream().filter(item -> item.position() == restorePosition).findFirst()
                        .ifPresent(item -> openResult(entry, item));
            }
        });
    }

    String openId() { return openId; }
    boolean candidatesOnly() { return candidatesOnly; }
    int selectedResultPosition() { return selectedResultPosition; }

    private void openResult(ScanHistory.Entry entry, ScanHistory.Result item) {
        LinearLayout body = ScanScreen.detailBody(activity);
        body.addView(ScanScreen.heading(activity, deviceLabel(entry, item), true));
        var classification = ScanScreen.detailSection(body, "기기 분류");
        ScanScreen.detailField(classification, "상태", item.candidate() ? "의심 기기"
                : item.classificationKnown() ? "미분류 신호" : "분류 정보 없음", false);
        if (item.candidate()) ScanScreen.detailField(classification, "기기 유형", item.category(), false);

        var metadata = ScanScreen.detailSection(body, "스캔 정보");
        if (item.transport() != null) {
            ScanScreen.detailField(metadata, "스캔 방식", item.transport(), false);
            ScanScreen.detailField(metadata, "신호 세기", item.rssi() == null ? "측정값 없음" : item.rssi() + " dBm", false);
            ScanScreen.detailField(metadata, "마지막 스캔", item.seen(), false);
        } else {
            metadata.addView(ScanScreen.label(activity, item.legacyMetadata()));
        }

        var evidence = ScanScreen.detailSection(body, "스캔 근거");
        String reason = recordedEvidence(item);
        evidence.addView(ScanScreen.label(activity, reason.isBlank() ? "저장된 스캔 근거가 없습니다." : reason));
        var scroll = new android.widget.ScrollView(activity);
        scroll.addView(body);
        selectedResultPosition = item.position();
        activity.showSettingsPage(R.id.history_device_detail, activity.getString(R.string.device_detail_title),
                scroll, () -> selectedResultPosition = -1);
    }

    private View sessionCard(ScanHistory.Entry entry) {
        MaterialCardView card = new MaterialCardView(activity);
        int spacing = activity.getResources().getDimensionPixelSize(R.dimen.space_small);
        int inset = activity.getResources().getDimensionPixelSize(R.dimen.space_medium);
        card.setRadius(inset);
        card.setStrokeWidth(Math.max(1, spacing / 8));
        card.setStrokeColor(activity.getColor(R.color.detail_section_outline));
        card.setCardBackgroundColor(activity.getColor(R.color.detail_section_background));
        card.setCardElevation(0);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> open(entry.id()));
        View contents = LayoutInflater.from(activity).inflate(R.layout.row_history_session, card, false);
        DateFormat date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        if (entry.legacyBody() != null) {
            TextView title = contents.findViewById(R.id.history_card_legacy_title);
            title.setText(entry.title());
            title.setVisibility(View.VISIBLE);
        }
        TextView started = contents.findViewById(R.id.history_card_started);
        TextView ended = contents.findViewById(R.id.history_card_ended);
        TextView counts = contents.findViewById(R.id.history_card_counts);
        started.setText(entry.started() > 0 ? date.format(new Date(entry.started())) : "—");
        ended.setText(entry.ended() > 0 ? date.format(new Date(entry.ended())) : "—");
        counts.setText(sessionCounts(entry));
        card.addView(contents);
        var params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = spacing;
        card.setLayoutParams(params);
        return card;
    }

    private static String sessionCounts(ScanHistory.Entry entry) {
        String total = entry.legacyBody() != null || (!entry.fullResults() && entry.olderCandidateOnly())
                ? "—" : entry.total() + "건";
        String candidates = entry.legacyBody() != null ? "—" : entry.candidates() + "건";
        return "전체 " + total + " · 의심 기기 " + candidates;
    }

    private static String deviceLabel(ScanHistory.Entry entry, ScanHistory.Result item) {
        return entry.fullResults() || entry.olderCandidateOnly() ? "기기 " + (item.position() + 1) : item.category();
    }

    private static String recordedEvidence(ScanHistory.Result item) {
        String prefix = "스캔 근거\n";
        return item.evidence().startsWith(prefix) ? item.evidence().substring(prefix.length()) : item.evidence();
    }

    private static final class HistoryResults extends RecyclerView.Adapter<ScanScreen.Row> {
        private final java.util.function.BiConsumer<ScanHistory.Entry, ScanHistory.Result> select;
        private ScanHistory.Entry session;
        private List<ScanHistory.Result> results = List.of();

        HistoryResults(java.util.function.BiConsumer<ScanHistory.Entry, ScanHistory.Result> select) {
            this.select = select;
        }

        void show(ScanHistory.Entry entry, List<ScanHistory.Result> visible) {
            session = entry;
            results = visible;
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return results.size(); }

        @NonNull @Override public ScanScreen.Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            var row = new ScanScreen.Row(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_observation, parent, false));
            row.targetsRow.setVisibility(View.GONE);
            row.badges.setVisibility(View.GONE);
            return row;
        }

        @Override public void onBindViewHolder(@NonNull ScanScreen.Row row, int position) {
            ScanHistory.Result item = results.get(position);
            row.title.setText(deviceLabel(session, item));
            row.status.setText(item.candidate() ? item.category() + " 의심 기기"
                    : item.classificationKnown() ? "미분류 신호" : "분류 정보 없음");
            ScanScreen.styleClassificationBadge(row.status, item.candidate());
            setField(row.metadata, item.transport() == null ? item.legacyMetadata() : item.transport());
            setField(row.signal, item.transport() == null ? null : item.rssi() == null ? "측정값 없음" : item.rssi() + " dBm");
            setField(row.seen, item.seen());
            setField(row.address, null);
            setField(row.nameOrigin, null);
            setField(row.level, null);
            String evidence = recordedEvidence(item);
            row.itemView.findViewById(R.id.result_evidence_title).setVisibility(evidence.isBlank() ? View.GONE : View.VISIBLE);
            row.reason.setText(evidence);
            row.reason.setVisibility(evidence.isBlank() ? View.GONE : View.VISIBLE);
            row.itemView.setOnClickListener(view -> {
                int index = row.getBindingAdapterPosition();
                if (index != RecyclerView.NO_POSITION) select.accept(session, results.get(index));
            });
        }

        private static void setField(TextView view, String value) {
            ((View) view.getParent()).setVisibility(value == null || value.isBlank() ? View.GONE : View.VISIBLE);
            view.setText(value);
        }
    }

    private void confirmDelete(String id) {
        new IconDialogBuilder(activity)
                .setTitle(id == null ? "전체 이력을 삭제할까요?" : "이 기록을 삭제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> history.delete(id, saved -> {
                    if (!activity.isDestroyed()) {
                        if (Boolean.TRUE.equals(saved)) {
                            if (id != null && id.equals(openId)) activity.closeSettingsPage();
                            refresh();
                        }
                        else error("이력을 삭제하지 못했습니다.");
                    }
                })).show();
    }

    void chooseRetention(ScanPreferences preferences) {
        int unlimited = ScanPreferences.UNLIMITED_RETENTION;
        int[] periods = {1, 5, 10, 30, 90, 180, 365, unlimited};
        String[] labels = new String[periods.length];
        int current = preferences.retentionDays();
        int index = 3;
        for (int i = 0; i < periods.length; i++) {
            labels[i] = periods[i] == unlimited ? activity.getString(R.string.history_retention_unlimited)
                    : activity.getString(R.string.history_retention_days, periods[i]);
            if (periods[i] == current) index = i;
        }
        int selected = index;
        activity.showSettingOptions(R.id.retention, activity.getString(R.string.settings_history_retention),
                labels, selected, which -> {
                    int days = periods[which];
                    if (days != unlimited && (current == unlimited || days < current)) {
                        new IconDialogBuilder(activity).setTitle(R.string.history_retention_shorten)
                                .setMessage(R.string.history_retention_delete_notice)
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton("적용", (confirm, button) -> applyRetention(days))
                                .setOnDismissListener(dialog -> activity.restoreSettingOption(selected)).show();
                    } else if (days != current) applyRetention(days);
                    else activity.closeSettingsPage();
                });
    }

    private void applyRetention(int days) {
        activity.closeSettingsPage();
        history.changeRetentionDays(days, saved -> {
            if (!Boolean.TRUE.equals(saved) && !activity.isDestroyed()) error("보관 기간을 적용하지 못했습니다.");
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
