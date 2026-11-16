package com.glassesscanner;

import android.content.Context;
import android.os.Parcelable;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

final class ScanScreen {
    private final ScannerActivity activity;
    private final ScanViewModel model;
    private final ScanPreferences preferences;
    private final RecyclerView list;
    private final Observations adapter;
    private final MaterialButton sort;
    private final MaterialButton action;
    private final MaterialButton recovery;
    private final MaterialButton clear;
    private final MaterialSwitch only;
    private final TextView status;
    private final TextView elapsed;
    private final TextView counts;
    private final TextView empty;
    private final View progress;
    private final LinearLayout detailContent;
    private final View detailPane;
    private final boolean wide;
    private Parcelable pendingScroll;
    private ScanObservation paneRow;
    private TextView paneMetadata;
    private AlertDialog compactDetail;
    private DeviceFinder finder;
    private String issue;
    private Runnable recover;

    ScanScreen(ScannerActivity activity, ScanViewModel model, ScanPreferences preferences) {
        this.activity = activity;
        this.model = model;
        this.preferences = preferences;
        // Keep results full width; device details open in a dialog on every screen size.
        wide = false;
        list = activity.findViewById(R.id.results);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setItemAnimator(null);
        list.setPreserveFocusAfterLayout(true);
        View header = activity.findViewById(R.id.scan_controls);
        sort = header.findViewById(R.id.sort);
        action = header.findViewById(R.id.scan_action);
        recovery = header.findViewById(R.id.recovery);
        clear = header.findViewById(R.id.clear);
        only = header.findViewById(R.id.only_candidates);
        status = header.findViewById(R.id.scan_status);
        elapsed = header.findViewById(R.id.scan_time);
        counts = header.findViewById(R.id.counts);
        empty = header.findViewById(R.id.empty);
        progress = header.findViewById(R.id.progress);
        detailContent = activity.findViewById(R.id.detail_content);
        detailPane = activity.findViewById(R.id.detail_pane);
        detailPane.setVisibility(wide ? View.VISIBLE : View.GONE);
        adapter = new Observations(activity::openObservation);
        list.setAdapter(adapter);
        action.setOnClickListener(view -> activity.scanAction());
        recovery.setOnClickListener(view -> { if (recover != null) recover.run(); });
        clear.setOnClickListener(view -> activity.clearResults());
        only.setChecked(model.candidatesOnly);
        only.setOnCheckedChangeListener((view, checked) -> {
            model.candidatesOnly = checked;
            render();
        });
        sort.setOnClickListener(activity::chooseSort);
    }

    boolean wide() { return wide; }

    void invalidateDetail() {
        paneRow = null;
        render();
    }

    void issue(String message, String label, Runnable action) {
        issue = message;
        recover = action;
        recovery.setText(label);
        render();
    }

    void restoreScroll(Parcelable state) { pendingScroll = state; }
    Parcelable saveScroll() { return list.getLayoutManager().onSaveInstanceState(); }

    void render() {
        ScanService.Snapshot scan = model.scan;
        boolean active = scan.state().active() || model.starting;
        setText(sort, model.signalSort ? "신호 세기순" : "후보 우선");
        action.setText(active ? R.string.scan_stop : R.string.scan_start);
        action.setIconResource(active ? R.drawable.ic_stop : R.drawable.ic_play);
        action.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                active ? 0xFFB3261E : 0xFF17653A));
        action.setTextColor(android.graphics.Color.WHITE);
        action.setIconTint(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
        recovery.setVisibility(!active && issue != null && recover != null ? View.VISIBLE : View.GONE);
        String stateText;
        if (issue != null && !active) stateText = issue;
        else if (model.starting) stateText = "스캔 준비 중";
        else if (scan.state() == ScanService.State.IDLE) stateText = "";
        else stateText = scan.message();
        if (scan.state().active() && !scan.background()) stateText += " · 화면을 보는 동안";
        setText(status, stateText);
        status.setVisibility(stateText.isEmpty() ? View.GONE : View.VISIBLE);
        if (active && progress.getVisibility() != View.VISIBLE) {
            ((com.google.android.material.progressindicator.LinearProgressIndicator) progress).setIndeterminate(true);
        }
        progress.setVisibility(active ? View.VISIBLE : View.GONE);
        updateClock();
        List<ScanObservation> visible = new ArrayList<>();
        for (ScanObservation row : scan.observations()) {
            if (!model.candidatesOnly || row.candidate()) visible.add(row);
        }
        Comparator<ScanObservation> signal = Comparator.comparingInt(ScanObservation::rssi).reversed();
        Comparator<ScanObservation> priority = Comparator.comparingInt(ScanObservation::strength).reversed();
        visible.sort((model.signalSort ? signal : priority.thenComparing(signal)).thenComparing(ScanObservation::key));
        setText(counts, "전체 " + scan.observations().size() + "건 · 후보 " + scan.candidates() + "건"
                + (scan.capacityReached() ? " · 목록 한도 도달" : ""));
        clear.setVisibility(!active && !scan.observations().isEmpty() ? View.VISIBLE : View.GONE);
        empty.setVisibility(visible.isEmpty() && (scan.state() != ScanService.State.IDLE || active)
                ? View.VISIBLE : View.GONE);
        if (visible.isEmpty()) {
            if (scan.state() == ScanService.State.IDLE && !active) setText(empty, "");
            else if (scan.observations().isEmpty() && active) setText(empty, "주변 신호를 기다리는 중입니다.");
            else if (scan.observations().isEmpty()) setText(empty, "이번 스캔에서 관측된 신호가 없습니다.");
            else setText(empty, "후보가 없습니다. ‘후보만’을 끄면 전체 결과를 볼 수 있습니다.");
        }
        if (only.isChecked() != model.candidatesOnly) only.setChecked(model.candidatesOnly);
        adapter.submitList(visible, () -> {
            if (pendingScroll != null && (scan.id() != null || !visible.isEmpty())) {
                list.getLayoutManager().onRestoreInstanceState(pendingScroll);
                pendingScroll = null;
            }
        });
        if (wide) {
            ScanObservation row = find(model.selected);
            if (!sameDetail(row, paneRow) || detailContent.getChildCount() == 0) {
                paneRow = row;
                detailContent.removeAllViews();
                if (row == null) detailContent.addView(label(activity, "목록에서 기기를 선택하면 상세 정보가 표시됩니다."));
                else paneMetadata = populateDetails(detailContent, row, preferences.advanced(), activity);
            } else if (row != null) {
                setText(paneMetadata, row.metadata());
            }
        }
    }

    void updateClock() {
        if (finder != null) finder.render();
        ScanService.Snapshot scan = model.scan;
        if (scan.state() != ScanService.State.RUNNING) {
            elapsed.setVisibility(View.GONE);
            return;
        }
        elapsed.setVisibility(View.VISIBLE);
        long spent = Math.max(0, SystemClock.elapsedRealtime() - scan.started());
        String value = scan.duration() > 0 ? "남은 시간 " + time(Math.max(0, scan.duration() - spent))
                : "경과 시간 " + time(spent);
        setText(elapsed, value);
    }

    ScanObservation find(String key) {
        if (key == null) return null;
        for (ScanObservation row : model.scan.observations()) if (row.key().equals(key)) return row;
        return null;
    }

    void showObservation(ScanObservation row) {
        if (wide) {
            model.selected = row.key();
            model.selectedSession = model.scan.id();
            render();
        } else {
            if (compactDetail != null) compactDetail.dismiss();
            model.selected = row.key();
            model.selectedSession = model.scan.id();
            android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
            LinearLayout body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            int inset = activity.getResources().getDimensionPixelSize(R.dimen.space_medium);
            body.setPadding(inset, 0, inset, inset);
            populateDetails(body, row, preferences.advanced(), activity);
            scroll.addView(body);
            AlertDialog opened = new IconDialogBuilder(activity)
                    .setTitle(row.name()).setView(scroll).setPositiveButton("닫기", null).show();
            compactDetail = opened;
            opened.setOnDismissListener(dialog -> {
                if (compactDetail != opened) return;
                compactDetail = null;
                if (!activity.isChangingConfigurations()) {
                    model.selected = null;
                    model.selectedSession = null;
                }
            });
        }
    }

    void restoreObservation() {
        if (finder != null) return;
        if (model.findingKey != null) {
            ScanObservation target = find(model.findingKey);
            if (target != null) showFinder(target);
            return;
        }
        if (wide || compactDetail != null) return;
        ScanObservation row = find(model.selected);
        if (row != null) showObservation(row);
    }

    void showFinder(ScanObservation row) {
        if (compactDetail != null) compactDetail.dismiss();
        if (finder != null) finder.dialog.dismiss();
        model.findingKey = row.key();
        finder = new DeviceFinder(activity, row, model.scan.id(), () -> {
            finder = null;
            if (!activity.isChangingConfigurations()) model.findingKey = null;
        });
    }

    void close() {
        if (compactDetail != null) compactDetail.dismiss();
        if (finder != null) finder.dialog.dismiss();
    }

    private static boolean sameDetail(ScanObservation first, ScanObservation second) {
        if (first == null || second == null) return first == second;
        return first.key().equals(second.key()) && Objects.equals(first.name(), second.name())
                && first.matches().equals(second.matches())
                && first.signal().companies().equals(second.signal().companies())
                && first.signal().advertisedUuids().equals(second.signal().advertisedUuids())
                && first.signal().serviceDataKeys().equals(second.signal().serviceDataKeys());
    }

    private static TextView populateDetails(LinearLayout body, ScanObservation row, boolean advanced, ScannerActivity activity) {
        Context context = body.getContext();
        TextView title = label(context, row.name());
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        body.addView(title);
        TextView metadata = label(context, row.metadata());
        body.addView(metadata);
        if (row.matches().isEmpty()) body.addView(label(context, "스마트글래스 후보 규칙과 일치하는 신호가 없습니다."));
        for (GlassesMatcher.Match match : row.matches()) body.addView(label(context, match.label() + "\n" + match.reason()));
        if (advanced) body.addView(label(context, row.radioEvidence()));
        MaterialButton locate = new MaterialButton(context);
        locate.setText("위치 찾기");
        locate.setOnClickListener(view -> activity.findDevice(row));
        body.addView(locate);
        return metadata;
    }

    static TextView label(Context context, String value) {
        TextView text = new TextView(context);
        text.setText(value);
        int inset = context.getResources().getDimensionPixelSize(R.dimen.space_small);
        text.setPadding(0, inset, 0, inset);
        text.setTextColor(context.getColor(R.color.on_surface));
        return text;
    }

    private static void setText(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private static String time(long millis) {
        long seconds = (millis + 999) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static final class Observations extends ListAdapter<ScanObservation, Row> {
        private final Consumer<ScanObservation> select;
        Observations(Consumer<ScanObservation> select) {
            super(new DiffUtil.ItemCallback<ScanObservation>() {
                @Override public boolean areItemsTheSame(@NonNull ScanObservation a, @NonNull ScanObservation b) {
                    return a.key().equals(b.key());
                }
                @Override public boolean areContentsTheSame(@NonNull ScanObservation a, @NonNull ScanObservation b) {
                    return a.equals(b);
                }
            });
            this.select = select;
        }

        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Row(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_observation, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Row holder, int position) {
            ScanObservation row = getItem(position);
            holder.title.setText(row.name());
            holder.metadata.setText(row.metadata());
            holder.reason.setText(row.candidate() ? row.best().label() + " · " + row.best().reason() : "일반 관측");
            holder.itemView.setOnClickListener(view -> select.accept(row));
        }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView metadata;
        final TextView reason;

        Row(View view) {
            super(view);
            title = view.findViewById(R.id.result_title);
            metadata = view.findViewById(R.id.result_metadata);
            reason = view.findViewById(R.id.result_reason);
        }
    }
}
