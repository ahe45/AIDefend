package com.glassesscanner;

import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.function.Consumer;

final class TargetsEditor {
    private enum Kind {
        NAME("기기 이름 또는 SSID", "device_names"), UUID("서비스 UUID", "service_uuids"),
        COMPANY("제조사 ID", "manufacturer_ids");

        final String label;
        final String key;
        Kind(String label, String key) { this.label = label; this.key = key; }
    }

    private record Rule(Kind kind, String value) {
        String label() { return kind.label + " · " + value; }
    }

    private record Target(DeviceCatalog.Entry device, Rule rule) {
        boolean builtin() { return device != null; }
        String key() { return builtin() ? "builtin/" + device.label() : rule.kind().key + "/" + rule.value(); }
        String title() { return builtin() ? device.label() : rule.value(); }
        String models() { return builtin() ? device.models() : rule.kind().label; }
        String searchDetails() {
            if (!builtin()) return rule.kind().label;
            return device.evidence() + " " + device.source() + " "
                    + (device.name() == null ? "" : device.name().pattern());
        }
    }

    private final ScannerActivity activity;
    private final SavedTargets store;
    private AlertDialog browser;
    private TargetsAdapter adapter;
    private List<Target> targets = new ArrayList<>();
    private String query = "";
    private int filter = R.id.targets_all;

    TargetsEditor(ScannerActivity activity) {
        this.activity = activity;
        store = new SavedTargets(activity);
    }

    AlertDialog show() {
        if (browser != null && browser.isShowing()) return browser;
        View body = LayoutInflater.from(activity).inflate(R.layout.dialog_targets, null);
        RecyclerView list = body.findViewById(R.id.targets_list);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setItemAnimator(null);
        adapter = new TargetsAdapter(target -> {
            if (target.builtin()) showDetails(target.device());
            else edit(target.rule(), true);
        });
        list.setAdapter(adapter);
        TextInputEditText search = body.findViewById(R.id.targets_search);
        search.setText(query);
        search.setOnEditorActionListener((text, actionId, event) -> {
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            android.view.inputmethod.InputMethodManager keyboard = activity.getSystemService(
                    android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            return true;
        });
        ChipGroup filters = body.findViewById(R.id.targets_filters);
        filters.check(filter);
        AlertDialog opened = new IconDialogBuilder(activity).setTitle(R.string.targets_title).setView(body)
                .setPositiveButton(R.string.targets_add, null).setNegativeButton("닫기", null).create();
        browser = opened;
        opened.setOnDismissListener(dialog -> {
            if (browser == opened) {
                browser = null;
                adapter = null;
                targets = new ArrayList<>();
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                query = text.toString();
                renderTargets(true);
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        filters.setOnCheckedStateChangeListener((group, checked) -> {
            if (checked.isEmpty()) return;
            filter = checked.get(0);
            renderTargets(true);
        });
        opened.show();
        opened.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> chooseKind());
        int width = Math.min(dp(720), activity.getResources().getDisplayMetrics().widthPixels - dp(24));
        int height = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.88f);
        opened.getWindow().setLayout(width, height);
        opened.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        body.requestFocus();
        reloadTargets();
        return opened;
    }

    private void reloadTargets() {
        if (browser == null || !browser.isShowing()) return;
        List<Target> values = new ArrayList<>();
        for (DeviceCatalog.Entry device : DeviceCatalog.ENTRIES) values.add(new Target(device, null));
        List<Rule> custom = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            for (String value : entries(kind)) custom.add(new Rule(kind, value));
        }
        custom.sort(Comparator.comparing(Rule::label));
        for (Rule rule : custom) values.add(new Target(null, rule));
        targets = values;
        renderTargets(false);
    }

    private void renderTargets(boolean resetScroll) {
        if (browser == null || adapter == null) return;
        List<Target> visible = new ArrayList<>();
        for (Target target : targets) {
            if (filter == R.id.targets_builtin && !target.builtin()) continue;
            if (filter == R.id.targets_custom && target.builtin()) continue;
            if (matchesQuery(query, target.title(), target.models(), target.searchDetails())) visible.add(target);
        }
        RecyclerView list = browser.findViewById(R.id.targets_list);
        adapter.submitList(visible, () -> { if (resetScroll) list.scrollToPosition(0); });
        TextView count = browser.findViewById(R.id.targets_count);
        count.setText(activity.getString(R.string.targets_count, visible.size(), DeviceCatalog.ENTRIES.size(),
                targets.size() - DeviceCatalog.ENTRIES.size()));
        TextView empty = browser.findViewById(R.id.targets_empty);
        empty.setText(query.trim().isEmpty() && filter == R.id.targets_custom
                ? R.string.targets_no_custom : R.string.targets_no_results);
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
    }

    static boolean matchesQuery(String query, String... fields) {
        String text = normalizeSearch(String.join(" ", fields));
        for (String term : normalizeSearch(query).split("\\s+")) {
            if (!text.contains(term)) return false;
        }
        return true;
    }

    private static String normalizeSearch(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }

    private void showDetails(DeviceCatalog.Entry entry) {
        ScrollView scroll = new ScrollView(activity);
        TextView text = new TextView(activity);
        text.setPadding(dp(24), dp(8), dp(24), dp(16));
        text.setTextColor(activity.getColor(R.color.on_surface));
        text.setTextSize(16);
        text.setTextIsSelectable(true);
        String pattern = entry.name() == null ? "" : "\n\n" + activity.getString(R.string.targets_name_pattern)
                + "\n" + entry.name().pattern();
        text.setText(entry.models() + "\n\n" + entry.evidence() + pattern + "\n\n" + entry.source());
        android.text.util.Linkify.addLinks(text, android.text.util.Linkify.WEB_URLS);
        text.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        scroll.addView(text);
        new IconDialogBuilder(activity).setTitle(entry.label()).setView(scroll).setPositiveButton("닫기", null).show();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    void fromObservation(ScanObservation row) {
        List<Rule> rules = new ArrayList<>();
        if (row.signal().name() != null && !row.signal().name().trim().isEmpty()) {
            rules.add(new Rule(Kind.NAME, row.signal().name()));
        }
        Set<String> uuids = new HashSet<>(row.signal().advertisedUuids());
        uuids.addAll(row.signal().serviceDataKeys());
        uuids.stream().sorted().forEach(uuid -> rules.add(new Rule(Kind.UUID, uuid)));
        row.signal().companies().stream().sorted().forEach(id -> rules.add(new Rule(Kind.COMPANY, ScanObservation.company(id))));
        if (rules.isEmpty()) {
            chooseKind();
            return;
        }
        new IconDialogBuilder(activity).setTitle("탐색 대상에 추가")
                .setItems(rules.stream().map(Rule::label).toArray(String[]::new),
                        (dialog, index) -> edit(rules.get(index), false))
                .setNegativeButton("취소", null).show();
    }

    private void chooseKind() {
        Kind[] kinds = Kind.values();
        String[] labels = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) labels[i] = kinds[i].label;
        new IconDialogBuilder(activity).setTitle("탐색 기준 선택")
                .setItems(labels, (dialog, index) -> edit(new Rule(kinds[index], ""), false))
                .setNegativeButton("취소", null).show();
    }

    private void edit(Rule rule, boolean existing) {
        LinearLayout wrapper = new LinearLayout(activity);
        int inset = activity.getResources().getDimensionPixelSize(R.dimen.space_medium);
        wrapper.setPadding(inset, inset, inset, inset);
        TextInputLayout field = new TextInputLayout(activity);
        wrapper.addView(field, new LinearLayout.LayoutParams(-1, -2));
        TextInputEditText input = new TextInputEditText(field.getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setText(rule.value());
        field.setHint(rule.kind().label);
        field.addView(input);
        String guidance = switch (rule.kind()) {
            case NAME -> "기기의 전체 이름을 입력하세요.";
            case UUID -> "하이픈을 포함한 전체 UUID를 입력하세요.";
            case COMPANY -> "예시 0x03C2. 같은 제조사의 여러 모델이 후보에 포함될 수 있습니다.";
        };
        MaterialAlertDialogBuilder builder = new IconDialogBuilder(activity)
                .setTitle(existing ? "탐색 대상 수정" : "탐색 대상 추가")
                .setMessage(guidance + "\n변경은 다음 스캔부터 적용됩니다.").setView(wrapper)
                .setNegativeButton("취소", null).setPositiveButton("저장", null);
        if (existing) builder.setNeutralButton("삭제", (dialog, which) -> delete(rule));
        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String raw = input.getText() == null ? "" : input.getText().toString().trim();
            if (raw.isEmpty()) {
                field.setError("값을 입력하세요.");
                return;
            }
            String normalized;
            try {
                normalized = switch (rule.kind()) {
                    case NAME -> raw;
                    case UUID -> SavedTargets.normalizeUuid(raw);
                    case COMPANY -> SavedTargets.normalizeManufacturer(raw);
                };
            } catch (IllegalArgumentException invalid) {
                field.setError("입력 형식을 확인하세요.");
                return;
            }
            for (String value : entries(rule.kind())) {
                if (existing && value.equals(rule.value())) continue;
                if (value.equalsIgnoreCase(normalized)) {
                    field.setError("이미 등록된 값입니다.");
                    return;
                }
            }
            store.replace(rule.kind().key, existing ? rule.value() : null, normalized);
            dialog.dismiss();
            reloadTargets();
        });
    }

    private void delete(Rule rule) {
        new IconDialogBuilder(activity).setTitle("이 탐색 대상을 삭제할까요?")
                .setMessage(rule.label()).setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    store.replace(rule.kind().key, rule.value(), null);
                    reloadTargets();
                }).show();
    }

    private Set<String> entries(Kind kind) {
        return switch (kind) {
            case NAME -> store.values(false);
            case UUID -> store.values(true);
            case COMPANY -> store.manufacturerIds();
        };
    }

    private static final class TargetsAdapter extends ListAdapter<Target, TargetRow> {
        private final Consumer<Target> select;

        TargetsAdapter(Consumer<Target> select) {
            super(new DiffUtil.ItemCallback<Target>() {
                @Override public boolean areItemsTheSame(Target first, Target second) { return first.key().equals(second.key()); }
                @Override public boolean areContentsTheSame(Target first, Target second) { return first.equals(second); }
            });
            this.select = select;
        }

        @Override public TargetRow onCreateViewHolder(ViewGroup parent, int type) {
            return new TargetRow(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_target, parent, false));
        }

        @Override public void onBindViewHolder(TargetRow row, int position) {
            Target target = getItem(position);
            Context context = row.itemView.getContext();
            int color = context.getColor(target.builtin() ? R.color.brand : R.color.target_custom);
            GradientDrawable iconBackground = new GradientDrawable();
            iconBackground.setColor(ColorUtils.setAlphaComponent(color, 24));
            iconBackground.setCornerRadius(12 * context.getResources().getDisplayMetrics().density);
            row.icon.setBackground(iconBackground);
            row.icon.setImageResource(target.builtin() ? R.drawable.ic_permission_bluetooth : R.drawable.ic_action);
            row.icon.setImageTintList(ColorStateList.valueOf(color));
            GradientDrawable badgeBackground = new GradientDrawable();
            badgeBackground.setColor(ColorUtils.setAlphaComponent(color, 24));
            badgeBackground.setCornerRadius(20 * context.getResources().getDisplayMetrics().density);
            row.badge.setBackground(badgeBackground);
            row.badge.setTextColor(color);
            row.badge.setText(target.builtin() ? R.string.targets_filter_builtin : R.string.targets_filter_custom);
            row.title.setText(target.title());
            row.models.setText(target.models());
            row.evidence.setText(target.builtin() ? target.device().evidence().split("\n", 2)[0]
                    : context.getString(R.string.targets_custom_evidence));
            row.itemView.setOnClickListener(view -> select.accept(target));
        }
    }

    private static final class TargetRow extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView badge;
        final TextView title;
        final TextView models;
        final TextView evidence;

        TargetRow(View item) {
            super(item);
            icon = item.findViewById(R.id.target_icon);
            badge = item.findViewById(R.id.target_badge);
            title = item.findViewById(R.id.target_title);
            models = item.findViewById(R.id.target_models);
            evidence = item.findViewById(R.id.target_evidence);
        }
    }
}
