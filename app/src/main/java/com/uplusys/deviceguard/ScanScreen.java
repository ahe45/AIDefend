package com.uplusys.deviceguard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
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
    private final MaterialButton action;
    private final MaterialButton recovery;
    private final MaterialSwitch onlyCandidates;
    private final MaterialButton categories;
    private final TextView status;
    private final TextView counts;
    private final View emptyResults;
    private final TextView emptyTitle;
    private final TextView empty;
    private final LinearLayout detailContent;
    private final View detailPane;
    private final boolean wide;
    private Parcelable pendingScroll;
    private ScanObservation paneRow;
    private View observationPage;
    private LinearLayout observationBody;
    private ScanObservation observationRow;
    private DeviceFinder finder;
    private String issue;
    private Runnable recover;
    private int visibleScanDots = -2;
    private long displayedScanSeconds = -1;

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
        action = header.findViewById(R.id.scan_action);
        recovery = header.findViewById(R.id.recovery);
        onlyCandidates = header.findViewById(R.id.only_candidates);
        categories = header.findViewById(R.id.select_categories);
        status = header.findViewById(R.id.scan_status);
        counts = header.findViewById(R.id.counts);
        emptyResults = header.findViewById(R.id.empty_results);
        emptyTitle = header.findViewById(R.id.empty_results_title);
        empty = header.findViewById(R.id.empty);
        detailContent = activity.findViewById(R.id.detail_content);
        detailPane = activity.findViewById(R.id.detail_pane);
        detailPane.setVisibility(wide ? View.VISIBLE : View.GONE);
        adapter = new Observations(activity::openObservation);
        list.setAdapter(adapter);
        action.setOnClickListener(view -> activity.scanAction());
        recovery.setOnClickListener(view -> { if (recover != null) recover.run(); });
        categories.setOnClickListener(view -> activity.chooseCategories());
        onlyCandidates.setOnCheckedChangeListener((view, checked) -> {
            if (model.candidatesOnly == checked) return;
            model.candidatesOnly = checked;
            render();
        });
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
        SessionConfig config = scan.config();
        onlyCandidates.setChecked(model.candidatesOnly);
        java.util.Set<RadioScanner.Mode> pathsToDisplay = config == null ? scan.paths().keySet()
                : new java.util.HashSet<>(config.paths());
        var selectedStatuses = scan.paths().entrySet().stream().filter(entry -> pathsToDisplay.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toList());
        boolean completedPath = selectedStatuses.stream().map(java.util.Map.Entry::getValue)
                .anyMatch(path -> path.state() == RadioScanner.PathState.COMPLETE);
        boolean failedPath = selectedStatuses.stream().map(java.util.Map.Entry::getValue).anyMatch(path -> path.state() == RadioScanner.PathState.FAILED
                || path.state() == RadioScanner.PathState.PERMISSION_REQUIRED || path.state() == RadioScanner.PathState.THROTTLED
                || path.state() == RadioScanner.PathState.STALE);
        action.setIconResource(active ? R.drawable.ic_stop : R.drawable.ic_play);
        action.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                active ? 0xFFB3261E : 0xFF17653A));
        action.setTextColor(android.graphics.Color.WHITE);
        action.setIconTint(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
        recovery.setVisibility(!active && issue != null && recover != null ? View.VISIBLE : View.GONE);
        String stateText;
        if (issue != null && !active) stateText = issue;
        else if (active || scan.state() == ScanService.State.IDLE) stateText = "";
        else stateText = scan.message();
        setText(status, stateText);
        status.setVisibility(stateText.isEmpty() ? View.GONE : View.VISIBLE);
        updateClock();
        List<ScanObservation> visible = new ArrayList<>();
        for (ScanObservation row : scan.observations()) {
            if (!model.candidatesOnly || row.candidate()) visible.add(row);
        }
        Comparator<ScanObservation> signal = Comparator.comparingInt(ScanObservation::rssi).reversed();
        Comparator<ScanObservation> priority = Comparator.comparingInt(ScanObservation::strength).reversed();
        visible.sort(priority.thenComparing(signal).thenComparing(ScanObservation::key));
        int categoryCount = config == null ? activity.selectedCategoryCount() : config.categories().size();
        setText(counts, activity.getString(R.string.scan_counts, categoryCount, scan.observations().size(), scan.candidates())
                + (scan.capacityReached() ? " · 목록 한도 도달" : ""));
        emptyResults.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        if (visible.isEmpty()) {
            boolean hiddenByFilter = model.candidatesOnly && !scan.observations().isEmpty();
            setText(emptyTitle, activity.getString(hiddenByFilter ? R.string.scan_empty_candidates : R.string.scan_empty_title));
            if (hiddenByFilter) setText(empty, activity.getString(R.string.scan_empty_filter_hint));
            else if (failedPath && !completedPath) setText(empty, "검색 권한과 기기 설정을 확인하세요.");
            else if (active) setText(empty, "선택한 유형의 새 신호를 기다리는 중입니다.");
            else setText(empty, activity.getString(R.string.scan_empty_guide));
        }
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
                else populateDetails(detailContent, row);
            } else if (row != null) {
                updateDetail(detailContent, row);
                paneRow = row;
            }
        }
        if (observationBody != null) {
            ScanObservation row = find(model.selected);
            if (row != null) {
                if (!sameDetail(row, observationRow)) {
                    observationBody.removeAllViews();
                    populateDetails(observationBody, row);
                } else updateDetail(observationBody, row);
                observationRow = row;
            }
        }
    }

    void updateClock() {
        ScanService.Snapshot scan = model.scan;
        boolean active = scan.state().active() || model.starting;
        long elapsedSeconds = scan.state().active() && scan.started() > 0
                ? Math.max(0, SystemClock.elapsedRealtime() - scan.started()) / 1000 : 0;
        updateScanActionLabel(active, elapsedSeconds);
        if (finder != null) finder.render();
        for (int i = 0; i < list.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = list.getChildViewHolder(list.getChildAt(i));
            if (holder instanceof Row row && row.observation != null) row.seen.setText(observedAge(row.observation));
        }
        if (observationBody != null && observationRow != null) updateDetail(observationBody, observationRow);
        if (paneRow != null) updateDetail(detailContent, paneRow);
    }

    private void updateScanActionLabel(boolean active, long elapsedSeconds) {
        // The activity's existing clock stops when it goes to the background.
        int dots = active ? (ValueAnimator.areAnimatorsEnabled() && action.isShown()
                ? (int) (elapsedSeconds % 4) : 0) : -1;
        if (dots == visibleScanDots && elapsedSeconds == displayedScanSeconds) return;
        visibleScanDots = dots;
        displayedScanSeconds = elapsedSeconds;
        if (!active) {
            action.setText(R.string.scan_start);
            action.setContentDescription(null);
            return;
        }
        String stop = activity.getString(R.string.scan_stop);
        String running = activity.getString(R.string.scan_running);
        String elapsed = time(elapsedSeconds);
        SpannableString label = new SpannableString(stop + "\n" + running + "... · " + elapsed);
        int subtitleStart = stop.length() + 1;
        label.setSpan(new RelativeSizeSpan(0.6f), subtitleStart, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (dots < 3) {
            // Reserve the same width for all phases so the label and stop icon stay in place.
            int dotsStart = subtitleStart + running.length();
            label.setSpan(new ForegroundColorSpan(android.graphics.Color.TRANSPARENT), dotsStart + dots, dotsStart + 3,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        action.setText(label);
        action.setContentDescription(activity.getString(R.string.scan_running_stop_description, elapsed));
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
            return;
        }
        activity.closeSettingsPage(observationPage);
        var body = detailBody(activity);
        populateDetails(body, row);
        var scroll = new android.widget.ScrollView(activity);
        scroll.addView(body);
        var locate = activity.settingsAction(R.id.device_detail_find, "위치 찾기", R.drawable.ic_permission_location, true);
        locate.setOnClickListener(view -> activity.findDevice(observationRow));
        View page = activity.settingsWithActions(scroll, locate);
        activity.showSettingsPage(R.id.scan_device_detail, activity.getString(R.string.device_detail_title), page, () -> {
            if (observationPage != page) return;
            observationPage = null;
            observationBody = null;
            observationRow = null;
            model.selected = null;
            model.selectedSession = null;
        });
        observationPage = page;
        observationBody = body;
        observationRow = row;
        model.selected = row.key();
        model.selectedSession = model.scan.id();
    }

    void restoreObservation() {
        if (finder != null || activity.hasSettingsPage()) return;
        if (model.findingKey != null) {
            ScanObservation target = find(model.findingKey);
            if (target != null) {
                if (target.key().equals(model.selected)) showObservation(target);
                showFinder(target);
            }
            return;
        }
        if (wide || observationPage != null) return;
        ScanObservation row = find(model.selected);
        if (row != null) showObservation(row);
    }

    void showFinder(ScanObservation row) {
        if (finder != null) finder.dialog.dismiss();
        model.findingKey = row.key();
        finder = new DeviceFinder(activity, row, model.scan.id(), () -> {
            finder = null;
            if (!activity.isChangingConfigurations()) model.findingKey = null;
        });
    }

    void close() {
        if (!activity.isChangingConfigurations() && !activity.isFinishing()) activity.closeSettingsPage(observationPage);
        if (finder != null) finder.dialog.dismiss();
    }

    private static boolean sameDetail(ScanObservation first, ScanObservation second) {
        if (first == null || second == null) return first == second;
        return first.key().equals(second.key()) && Objects.equals(first.name(), second.name())
                && first.matches().equals(second.matches())
                && first.signal().companies().equals(second.signal().companies())
                && first.signal().advertisedUuids().equals(second.signal().advertisedUuids())
                && first.signal().serviceDataKeys().equals(second.signal().serviceDataKeys())
                && first.signal().manufacturerData().equals(second.signal().manufacturerData())
                && first.signal().serviceData().equals(second.signal().serviceData())
                && first.signal().previousResult() == second.signal().previousResult()
                && first.signal().nameOrigin() == second.signal().nameOrigin()
                && Objects.equals(first.signal().advertisedName(), second.signal().advertisedName())
                && Objects.equals(first.signal().cachedName(), second.signal().cachedName())
                && Objects.equals(first.signal().wifiFrequency(), second.signal().wifiFrequency())
                && Objects.equals(first.signal().bluetoothClass(), second.signal().bluetoothClass())
                && Objects.equals(first.signal().appearance(), second.signal().appearance());
    }

    private static void updateDetail(LinearLayout body, ScanObservation row) {
        TextView signal = body.findViewWithTag("observation_signal");
        TextView observed = body.findViewWithTag("observation_time");
        if (signal != null) setText(signal, signalValue(row));
        if (observed != null) setText(observed, observedAge(row));
    }

    private static String signalValue(ScanObservation row) {
        return row.signal().rssi() == null ? "측정값 없음" : row.signal().rssi() + " dBm";
    }

    private void populateDetails(LinearLayout body, ScanObservation row) {
        Context context = body.getContext();
        body.addView(heading(context, row.name(), true));
        var classification = detailSection(body, "기기 분류");
        detailField(classification, "상태", row.candidate() ? "의심 기기" : "미분류 신호", false);
        detailField(classification, "기기 유형", categoryLabel(row), false);
        if (DeviceMatcher.conflict(row.matches())) {
            classification.addView(label(context, "여러 유형의 규칙이 일치해 경보를 보류했습니다."));
        }

        var metadata = detailSection(body, "스캔 정보");
        detailField(metadata, "스캔 방식", row.signal().source().label, false);
        detailField(metadata, "신호 세기", signalValue(row), false).setTag("observation_signal");
        detailField(metadata, "기기 주소", row.signal().address(), false);
        if (row.signal().wifiFrequency() != null) {
            detailField(metadata, "주파수", row.signal().wifiFrequency() + " MHz", false);
        }
        detailField(metadata, "마지막 스캔", observedAge(row), false).setTag("observation_time");
        detailField(metadata, "이름 출처", row.signal().nameOrigin().label, false);

        var evidence = detailSection(body, "스캔 근거");
        if (row.matches().isEmpty()) {
            evidence.addView(label(context, "등록된 스캔 기준과 일치하는 신호가 없습니다."));
        } else {
            detailField(evidence, "일치 항목", DetectionText.targets(row), false);
            detailField(evidence, "근거 상태", evidenceLevels(row), false);
            for (DeviceMatcher.Evidence match : DetectionText.evidence(row)) {
                detailDivider(evidence);
                evidence.addView(heading(context, match.type(), false));
                detailField(evidence, "수신값", match.received(), true);
                detailField(evidence, "일치 기준", match.criterion(), true);
            }
            var explanation = detailSection(body, "판별 설명");
            row.matches().stream().map(DeviceMatcher.Match::reason).distinct()
                    .forEach(reason -> explanation.addView(label(context, DetectionText.display(reason))));
            explanation.addView(label(context, "실제 제품 종류와 촬영 여부는 별도 확인 필요."));
            row.matches().stream().map(DeviceMatcher.Match::sourceUrl)
                    .filter(value -> value != null && !value.isBlank()).distinct().forEach(url -> {
                TextView sources = detailField(explanation, "근거 자료", url, true);
                android.text.util.Linkify.addLinks(sources, android.text.util.Linkify.WEB_URLS);
                sources.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            });
        }
        if (preferences.advanced()) {
            var signal = row.signal();
            if (!signal.companies().isEmpty() || !signal.advertisedUuids().isEmpty() || !signal.serviceDataKeys().isEmpty()) {
                var rawData = detailSection(body, "무선 데이터");
                signal.companies().stream().sorted().forEach(id -> detailField(rawData,
                        "제조사 ID " + ScanObservation.company(id), signal.manufacturerData().getOrDefault(id, "미수집"), true));
                signal.advertisedUuids().stream().sorted().forEach(uuid ->
                        detailField(rawData, "광고 서비스 UUID", uuid, true));
                signal.serviceDataKeys().stream().sorted().forEach(uuid -> {
                    detailField(rawData, "서비스 데이터 키", uuid, true);
                    detailField(rawData, "서비스 데이터 값", signal.serviceData().getOrDefault(uuid, "미수집"), true);
                });
            }
            MaterialButton register = new MaterialButton(context);
            register.setText("스캔 대상으로 추가");
            register.setIconResource(R.drawable.ic_action);
            register.setOnClickListener(view -> activity.registerTarget(row));
            body.addView(register);
        }
        if (wide) {
            MaterialButton locate = new MaterialButton(context);
            locate.setText("위치 찾기");
            locate.setIconResource(R.drawable.ic_permission_location);
            locate.setOnClickListener(view -> activity.findDevice(row));
            body.addView(locate);
        }
    }

    static LinearLayout detailBody(Context context) {
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int inset = context.getResources().getDimensionPixelSize(R.dimen.space_medium);
        body.setPadding(inset, inset, inset, inset);
        return body;
    }

    static LinearLayout detailSection(LinearLayout body, String title) {
        Context context = body.getContext();
        int inset = context.getResources().getDimensionPixelSize(R.dimen.space_medium);
        int gap = context.getResources().getDimensionPixelSize(R.dimen.space_small);
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(inset, gap, inset, gap);
        var background = new android.graphics.drawable.GradientDrawable();
        background.setColor(context.getColor(R.color.detail_section_background));
        background.setCornerRadius(inset);
        background.setStroke(Math.max(1, gap / 8), context.getColor(R.color.detail_section_outline));
        section.setBackground(background);
        var params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = gap;
        body.addView(section, params);
        section.addView(heading(context, title, true));
        return section;
    }

    static TextView heading(Context context, String title, boolean section) {
        TextView heading = label(context, title);
        heading.setTextSize(section ? 17 : 15);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setTextColor(context.getColor(section ? R.color.brand : R.color.on_surface));
        androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
        return heading;
    }

    static TextView detailField(LinearLayout section, String name, String value, boolean stacked) {
        Context context = section.getContext();
        int gap = context.getResources().getDimensionPixelSize(R.dimen.space_small);
        LinearLayout field = new LinearLayout(context);
        field.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        field.setPadding(0, gap / 2, 0, gap / 2);
        section.addView(field, new LinearLayout.LayoutParams(-1, -2));
        TextView key = new TextView(context);
        key.setText(name);
        key.setTextSize(13);
        key.setTextColor(context.getColor(R.color.muted));
        key.setPaddingRelative(0, 0, stacked ? 0 : gap, 0);
        TextView content = new TextView(context);
        content.setText(value);
        content.setTextSize(15);
        content.setTextColor(context.getColor(R.color.on_surface));
        if (stacked) content.setTextIsSelectable(true);
        field.addView(key, stacked ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        field.addView(content, stacked ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 2));
        return content;
    }

    private static void detailDivider(LinearLayout section) {
        Context context = section.getContext();
        View line = new View(context);
        line.setBackgroundColor(context.getColor(R.color.detail_section_outline));
        var params = new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(context.getResources().getDisplayMetrics().density)));
        params.topMargin = context.getResources().getDimensionPixelSize(R.dimen.space_small);
        section.addView(line, params);
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

    private static String observedAge(ScanObservation row) {
        long timestamp = row.signal().observedAtElapsed();
        if (timestamp <= 0) return "시각 정보 없음";
        long seconds = Math.max(0, SystemClock.elapsedRealtime() - timestamp) / 1000;
        return (row.signal().previousResult() ? "이전 결과 · " : "") + seconds + "초 전";
    }

    private static String categoryLabel(ScanObservation row) {
        String categories = row.matches().stream().filter(DeviceMatcher.Match::classified).map(DeviceMatcher.Match::category)
                .distinct().map(category -> category.label).collect(java.util.stream.Collectors.joining(" · "));
        String features = row.matches().stream().flatMap(match -> match.featureTags().stream()).distinct()
                .collect(java.util.stream.Collectors.joining(" · "));
        return (categories.isEmpty() ? "미분류" : categories) + (features.isEmpty() ? "" : " · " + features);
    }

    private static String evidenceLevels(ScanObservation row) {
        String levels = row.matches().stream().map(DeviceMatcher.Match::evidenceLevel).distinct()
                .map(level -> level.label).collect(java.util.stream.Collectors.joining(" · "));
        if (!row.matches().isEmpty() && row.matches().stream().noneMatch(match -> match.evidenceLevel() == DetectionRule.EvidenceLevel.MEASURED))
            levels += " · 실기 검증 전";
        return levels.isEmpty() ? "일치 근거 없음" : levels;
    }

    private static String time(long seconds) {
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
            holder.observation = row;
            holder.title.setText(row.name());
            holder.metadata.setText(row.signal().source().label + (row.signal().wifiFrequency() == null ? ""
                    : " · " + row.signal().wifiFrequency() + " MHz"));
            holder.signal.setText(row.signal().rssi() == null ? "측정값 없음" : row.signal().rssi() + " dBm");
            holder.address.setText(row.signal().address());
            holder.status.setText(DeviceMatcher.conflict(row.matches()) ? "유형 충돌 · 경보 보류"
                    : row.candidate() ? categoryLabel(row) + " 의심 기기" : "미분류 신호");
            holder.seen.setText(observedAge(row));
            holder.level.setText(evidenceLevels(row));
            holder.nameOrigin.setText(row.signal().nameOrigin().label);
            styleClassificationBadge(holder.status, row.candidate());
            holder.targets.setText(DetectionText.targets(row));
            holder.targetsRow.setVisibility(row.candidate() ? View.VISIBLE : View.GONE);
            holder.badges.removeAllViews();
            DetectionText.evidence(row).stream().map(DeviceMatcher.Evidence::type).distinct().forEach(type -> {
                com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(holder.itemView.getContext());
                chip.setText(type);
                chip.setCheckable(false);
                chip.setClickable(false);
                chip.setFocusable(false);
                holder.badges.addView(chip);
            });
            holder.badges.setVisibility(row.matches().isEmpty() ? View.GONE : View.VISIBLE);
            holder.reason.setText(row.matches().isEmpty() ? "등록된 탐색 기준과 일치하는 신호 없음" : DetectionText.summary(row));
            holder.itemView.setOnClickListener(view -> select.accept(row));
        }
    }

    static void styleClassificationBadge(TextView status, boolean candidate) {
        status.setTextColor(candidate ? 0xFF8B1726 : 0xFF253C53);
        var badge = new android.graphics.drawable.GradientDrawable();
        badge.setColor(candidate ? 0xFFFDE7E9 : 0xFFDFE7F2);
        badge.setCornerRadius(20 * status.getResources().getDisplayMetrics().density);
        status.setBackground(badge);
    }

    static final class Row extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView metadata;
        final TextView reason;
        final TextView status;
        final TextView signal;
        final TextView address;
        final TextView targets;
        final TextView seen;
        final TextView level;
        final TextView nameOrigin;
        ScanObservation observation;
        final View targetsRow;
        final com.google.android.material.chip.ChipGroup badges;

        Row(View view) {
            super(view);
            title = view.findViewById(R.id.result_title);
            metadata = view.findViewById(R.id.result_metadata);
            reason = view.findViewById(R.id.result_reason);
            status = view.findViewById(R.id.result_status);
            signal = view.findViewById(R.id.result_signal);
            address = view.findViewById(R.id.result_address);
            targets = view.findViewById(R.id.result_targets);
            seen = view.findViewById(R.id.result_seen);
            level = view.findViewById(R.id.result_level);
            nameOrigin = view.findViewById(R.id.result_name_origin);
            targetsRow = view.findViewById(R.id.result_targets_row);
            badges = view.findViewById(R.id.result_evidence_badges);
        }
    }
}
