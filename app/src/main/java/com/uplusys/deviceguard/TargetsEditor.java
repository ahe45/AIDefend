package com.uplusys.deviceguard;

import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class TargetsEditor {
    private record CategoryOption(int label, DeviceCategory category) {}

    private static final List<CategoryOption> CATEGORY_OPTIONS = List.of(
            new CategoryOption(R.string.targets_filter_all, null),
            new CategoryOption(R.string.targets_category_glasses, DeviceCategory.GLASSES),
            new CategoryOption(R.string.targets_category_camera, DeviceCategory.CAMERA),
            new CategoryOption(R.string.targets_category_audio, DeviceCategory.AUDIO),
            new CategoryOption(R.string.targets_category_watch, DeviceCategory.WATCH));

    private record Target(DeviceCatalog.Entry device, DetectionRule rule) {
        boolean builtin() { return device != null; }
        DeviceCategory category() { return builtin() ? device.category() : rule.category(); }
        String key() { return builtin() ? "builtin/" + device.label() : rule.id(); }
        String title() { return builtin() ? device.label() : rule.value(); }
        String models() { return builtin() ? device.category().label + " · " + device.models()
                : rule.category().label + " · " + rule.source().label + " · " + rule.field().label; }
        String searchDetails() {
            if (!builtin()) return models() + " " + rule.evidenceLevel().label + " " + rule.modelScope();
            return device.evidence() + " " + device.source() + " "
                    + (device.name() == null ? "" : device.name().pattern());
        }
    }

    private final ScannerActivity activity;
    private final SavedTargets store;
    private View browser;
    private TargetsAdapter adapter;
    private List<Target> targets = new ArrayList<>();
    private String query = "";
    private int filter = R.id.targets_all;
    private CategoryOption categoryFilter = CATEGORY_OPTIONS.get(0);
    private java.util.function.Supplier<Bundle> saveEditor;
    private String openBuiltin;

    TargetsEditor(ScannerActivity activity) {
        this.activity = activity;
        store = new SavedTargets(activity);
    }

    View show() {
        if (browser != null) return browser;
        View body = LayoutInflater.from(activity).inflate(R.layout.dialog_targets, null);
        RecyclerView list = body.findViewById(R.id.targets_list);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setItemAnimator(null);
        adapter = new TargetsAdapter(target -> {
            if (target.builtin()) showDetails(target.device());
            else edit(target.rule(), true);
        });
        list.setAdapter(adapter);
        var add = activity.settingsAction(R.id.settings_page_add,
                activity.getString(R.string.targets_add), R.drawable.ic_action, true);
        add.setOnClickListener(view -> chooseKind());
        browser = activity.settingsWithActions(body, add);
        activity.showSettingsPage(R.id.manage_targets, activity.getString(R.string.targets_title), browser, () -> {
            browser = null;
            adapter = null;
            targets = new ArrayList<>();
        }, new ScannerActivity.PageAction(R.id.targets_search_action, R.string.targets_search_title,
                R.drawable.ic_search, this::showSearch),
                new ScannerActivity.PageAction(R.id.targets_filter_action, R.string.targets_filter_title,
                        R.drawable.ic_filter, this::showFilters));
        reloadTargets();
        return browser;
    }

    private void showSearch() {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_targets_search, null);
        TextInputEditText search = content.findViewById(R.id.targets_search);
        search.setText(query);
        search.setSelection(search.length());
        AlertDialog dialog = new IconDialogBuilder(activity).setTitle(R.string.targets_search_title)
                .setView(content).setPositiveButton(R.string.confirm, null).create();
        search.setOnEditorActionListener((text, actionId, event) -> {
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            dialog.dismiss();
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                query = text.toString();
                renderTargets(true);
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        search.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }

    private void showFilters() {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_targets_filters, null);
        android.widget.RadioGroup categories = content.findViewById(R.id.targets_category);
        for (CategoryOption option : CATEGORY_OPTIONS) {
            var button = new MaterialRadioButton(activity);
            button.setId(View.generateViewId());
            button.setText(option.label());
            button.setMinHeight(dp(48));
            categories.addView(button);
            if (option.equals(categoryFilter)) categories.check(button.getId());
        }
        categories.setOnCheckedChangeListener((group, checkedId) -> {
            int index = group.indexOfChild(group.findViewById(checkedId));
            if (index >= 0) {
                categoryFilter = CATEGORY_OPTIONS.get(index);
                renderTargets(true);
            }
        });
        ChipGroup filters = content.findViewById(R.id.targets_filters);
        filters.check(filter);
        filters.setOnCheckedStateChangeListener((group, checked) -> {
            if (checked.isEmpty()) return;
            filter = checked.get(0);
            renderTargets(true);
        });
        new IconDialogBuilder(activity).setTitle(R.string.targets_filter_title)
                .setView(content).setPositiveButton(R.string.confirm, null).show();
    }

    private void reloadTargets() {
        if (browser == null) return;
        List<Target> values = new ArrayList<>();
        for (DeviceCatalog.Entry device : store.builtins()) values.add(new Target(device, null));
        List<DetectionRule> custom = new ArrayList<>(store.rules());
        custom.sort(Comparator.comparing(DetectionRule::value));
        for (DetectionRule rule : custom) values.add(new Target(null, rule));
        targets = values;
        renderTargets(false);
    }

    private void renderTargets(boolean resetScroll) {
        if (browser == null || adapter == null) return;
        List<Target> visible = new ArrayList<>();
        for (Target target : targets) {
            if (categoryFilter.category() != null && categoryFilter.category() != target.category()) continue;
            if (filter == R.id.targets_builtin && !target.builtin()) continue;
            if (filter == R.id.targets_custom && target.builtin()) continue;
            if (matchesQuery(query, target.title(), target.models(), target.searchDetails())) visible.add(target);
        }
        RecyclerView list = browser.findViewById(R.id.targets_list);
        adapter.submitList(visible, () -> { if (resetScroll) list.scrollToPosition(0); });
        TextView count = browser.findViewById(R.id.targets_count);
        int builtinCount = (int) targets.stream().filter(Target::builtin).count();
        count.setText(activity.getString(R.string.targets_count, visible.size(), builtinCount,
                targets.size() - builtinCount));
        TextView empty = browser.findViewById(R.id.targets_empty);
        int emptyMessage = R.string.targets_no_results;
        if (query.trim().isEmpty()) {
            if (categoryFilter.category() != null) emptyMessage = R.string.targets_no_category_results;
            else if (filter == R.id.targets_custom) emptyMessage = R.string.targets_no_custom;
        }
        empty.setText(emptyMessage);
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
        int patternLabel = entry.paths().contains(RadioScanner.Mode.WIFI) ? R.string.targets_ssid_pattern
                : R.string.targets_name_pattern;
        String pattern = entry.name() == null ? "" : "\n\n" + activity.getString(patternLabel)
                + "\n" + entry.name().pattern();
        String paths = entry.paths().isEmpty() ? "" : "\n스캔 방식  " + entry.paths().stream().sorted()
                .map(mode -> mode.label).collect(java.util.stream.Collectors.joining(" · "));
        text.setText("기기 유형  " + entry.category().label + paths + "\n근거 상태  " + entry.evidenceLevel().label
                + " · 실기 검증 전\n\n" + entry.models() + "\n\n" + entry.evidence() + pattern + "\n\n" + entry.source()
                + "\n\n새로 수신한 신호가 등록 조건을 충족하면 알림 설정에 따라 경고를 표시합니다. 변경은 다음 스캔부터 적용됩니다.");
        android.text.util.Linkify.addLinks(text, android.text.util.Linkify.WEB_URLS);
        text.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        scroll.addView(text);
        var delete = activity.settingsAction(R.id.settings_page_delete, "삭제", R.drawable.ic_close, false);
        delete.setOnClickListener(view -> new IconDialogBuilder(activity)
                        .setTitle("이 탐색 대상을 삭제할까요?")
                        .setMessage(entry.label() + "\n\n" + activity.getString(R.string.targets_delete_help))
                        .setNegativeButton("취소", null).setPositiveButton("삭제", (confirmation, action) -> {
                            store.deleteBuiltin(entry);
                            activity.closeSettingsPage();
                            reloadTargets();
                            activity.preferencesChanged();
                        }).show());
        openBuiltin = entry.label();
        activity.showSettingsPage(R.id.manage_targets, entry.label(), activity.settingsWithActions(scroll, delete),
                () -> openBuiltin = null);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    void fromObservation(ScanObservation row) {
        RadioScanner.Signal signal = row.signal();
        List<DetectionRule> options = new ArrayList<>();
        DetectionRule.Source source = switch (signal.source()) {
            case WIFI -> DetectionRule.Source.WIFI;
            case CLASSIC -> DetectionRule.Source.CLASSIC;
            default -> DetectionRule.Source.BLE;
        };
        if (source == DetectionRule.Source.WIFI) {
            addObserved(options, source, DetectionRule.Field.SSID, signal.name());
        } else if (source == DetectionRule.Source.CLASSIC) {
            addObserved(options, source, DetectionRule.Field.CLASSIC_NAME, signal.name());
        } else {
            addObserved(options, source, DetectionRule.Field.BLE_ADVERTISED_NAME, signal.advertisedName());
            addObserved(options, source, DetectionRule.Field.DEVICE_NAME, signal.cachedName());
            signal.advertisedUuids().stream().sorted().forEach(value ->
                    addObserved(options, source, DetectionRule.Field.ADVERTISED_UUID, value));
            signal.serviceDataKeys().stream().sorted().forEach(value ->
                    addObserved(options, source, DetectionRule.Field.SERVICE_DATA_KEY, value));
            signal.companies().stream().sorted().forEach(value ->
                    addObserved(options, source, DetectionRule.Field.MANUFACTURER_ID, ScanObservation.company(value)));
        }
        if (options.isEmpty()) { chooseKind(); return; }
        new IconDialogBuilder(activity).setTitle("스캔 결과에서 대상 추가")
                .setItems(options.stream().map(rule -> rule.field().label + " · " + rule.value()).toArray(String[]::new),
                        (dialog, index) -> edit(options.get(index), false))
                .setNegativeButton("취소", null).show();
    }

    private void addObserved(List<DetectionRule> rules, DetectionRule.Source source, DetectionRule.Field field, String value) {
        if (value == null || value.isEmpty()) return;
        rules.add(new DetectionRule(java.util.UUID.randomUUID().toString(), DeviceCategory.CUSTOM, source, field,
                value, field != DetectionRule.Field.SSID, false, DetectionRule.EvidenceLevel.USER, "", ""));
    }

    private void chooseKind() {
        edit(new DetectionRule(java.util.UUID.randomUUID().toString(), DeviceCategory.CUSTOM,
                DetectionRule.Source.BLE, DetectionRule.Field.BLE_ADVERTISED_NAME, "새 기기", true, false,
                DetectionRule.EvidenceLevel.USER, "", ""), false);
    }

    private android.widget.Spinner selector(LinearLayout body, String title, String[] labels) {
        body.addView(ScanScreen.label(activity, title));
        android.widget.Spinner spinner = new android.widget.Spinner(activity);
        spinner.setAdapter(new android.widget.ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, labels));
        body.addView(spinner, new LinearLayout.LayoutParams(-1, dp(48)));
        return spinner;
    }

    private static DetectionRule.Field[] fields(DetectionRule.Source source) {
        return switch (source) {
            case WIFI -> new DetectionRule.Field[]{DetectionRule.Field.SSID};
            case CLASSIC -> new DetectionRule.Field[]{DetectionRule.Field.CLASSIC_NAME};
            case BLE -> new DetectionRule.Field[]{DetectionRule.Field.BLE_ADVERTISED_NAME, DetectionRule.Field.DEVICE_NAME,
                    DetectionRule.Field.ADVERTISED_UUID, DetectionRule.Field.SERVICE_DATA_KEY, DetectionRule.Field.MANUFACTURER_ID};
            case ANY -> new DetectionRule.Field[]{DetectionRule.Field.LEGACY_NAME, DetectionRule.Field.LEGACY_UUID};
        };
    }

    private static DetectionRule.Field[] fieldsForEditor(DetectionRule rule, DetectionRule.Source source) {
        if (source == DetectionRule.Source.ANY && rule.source() == source
                && rule.field() == DetectionRule.Field.MANUFACTURER_ID) {
            return new DetectionRule.Field[]{DetectionRule.Field.LEGACY_NAME, DetectionRule.Field.LEGACY_UUID,
                    DetectionRule.Field.MANUFACTURER_ID};
        }
        return fields(source);
    }

    private void edit(DetectionRule rule, boolean existing) { edit(rule, existing, null); }

    private void edit(DetectionRule rule, boolean existing, Bundle draft) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(4), dp(20), dp(12));
        DeviceCategory[] categories = java.util.Arrays.stream(DeviceCategory.values()).filter(DeviceCategory::selectable)
                .toArray(DeviceCategory[]::new);
        DetectionRule.Source[] sources = DetectionRule.Source.values();
        String[] categoryLabels = new String[categories.length + 1];
        categoryLabels[0] = existing && rule.category() == DeviceCategory.CUSTOM
                ? DeviceCategory.CUSTOM.label : "기기 종류 선택";
        for (int i = 0; i < categories.length; i++) categoryLabels[i + 1] = categories[i].label;
        android.widget.Spinner category = selector(body, "기기 종류 (필수)", categoryLabels);
        category.setId(R.id.target_device_category);
        category.setSaveEnabled(false);
        category.setSelection(java.util.Arrays.asList(categories).indexOf(rule.category()) + 1);
        if (draft != null) category.setSelection(draft.getInt("category", category.getSelectedItemPosition()));
        TextView categoryError = ScanScreen.label(activity, "기기 종류를 선택하세요.");
        categoryError.setVisibility(View.GONE);
        categoryError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(categoryError);
        if (existing && rule.category() == DeviceCategory.CUSTOM) {
            body.addView(ScanScreen.label(activity, DeviceCategory.CUSTOM.support));
        }
        android.widget.Spinner source = selector(body, "스캔 출처",
                java.util.Arrays.stream(sources).map(value -> value.label).toArray(String[]::new));
        source.setSelection(draft == null ? rule.source().ordinal() : draft.getInt("source", rule.source().ordinal()));
        android.widget.Spinner field = selector(body, "일치할 필드", new String[]{rule.field().label});
        TextInputLayout wrapper = new TextInputLayout(activity);
        wrapper.setHint("실제 스캔 값 · 전체 값 일치");
        TextInputEditText input = new TextInputEditText(wrapper.getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(draft == null ? (existing || !rule.value().equals("새 기기") ? rule.value() : "")
                : draft.getString("value", ""));
        wrapper.addView(input);
        body.addView(wrapper, new LinearLayout.LayoutParams(-1, -2));
        com.google.android.material.checkbox.MaterialCheckBox ignoreCase =
                new com.google.android.material.checkbox.MaterialCheckBox(activity);
        ignoreCase.setText("Bluetooth 이름의 대소문자 구분 없이 비교");
        ignoreCase.setChecked(draft == null ? rule.ignoreCase() : draft.getBoolean("ignoreCase"));
        body.addView(ignoreCase);
        com.google.android.material.checkbox.MaterialCheckBox alerts =
                new com.google.android.material.checkbox.MaterialCheckBox(activity);
        alerts.setText("정확한 사용자 등록 조건 일치 시 알림");
        alerts.setChecked(draft == null ? rule.alerts() : draft.getBoolean("alerts"));
        body.addView(alerts);
        body.addView(ScanScreen.label(activity, "SSID는 대소문자와 공백을 포함한 전체 값을 비교합니다. "
                + "제조사 ID·공용 UUID·캐시 이름·출처 미지정 조건은 자동 경고에 사용하지 않습니다.\n변경은 다음 스캔부터 적용됩니다."));
        final DetectionRule.Field[][] available = {fieldsForEditor(rule, sources[source.getSelectedItemPosition()])};
        DetectionRule.Field preferredField = draft == null ? rule.field()
                : DetectionRule.Field.valueOf(draft.getString("field", rule.field().name()));
        int[] shownSource = {-1};
        Runnable updateAlert = () -> {
            DetectionRule.Field selected = available[0][Math.max(0, field.getSelectedItemPosition())];
            boolean allowed = false;
            try {
                String value = input.getText() == null ? "" : input.getText().toString();
                allowed = new DetectionRule(rule.id(), rule.category(),
                        sources[source.getSelectedItemPosition()], selected, value, ignoreCase.isChecked(), true,
                        DetectionRule.EvidenceLevel.USER, "", "").preciseAlert();
            } catch (IllegalArgumentException invalid) { /* Incomplete values cannot opt in to alerts. */ }
            alerts.setEnabled(allowed);
            if (!allowed) alerts.setChecked(false);
            wrapper.setHelperText(switch (selected) {
                case SSID -> "스캔한 SSID 전체 값 · 대소문자와 앞뒤 공백까지 비교";
                case ADVERTISED_UUID, SERVICE_DATA_KEY, LEGACY_UUID -> "128비트 UUID 전체 값 · 예: 0000180f-0000-1000-8000-00805f9b34fb";
                case MANUFACTURER_ID -> "16진수 회사 ID · 예: 0x01AB · 기기 종류를 확정하는 값이 아님";
                case DEVICE_NAME -> "Bluetooth에 저장된 이름 · 현재 광고 이름과 다를 수 있음";
                case LEGACY_NAME -> "기존 이름 조건 · 출처를 BLE 또는 Classic으로 지정해 범위를 좁힐 수 있음";
                default -> "스캔에서 확인된 이름 전체 값 · 이름 일부나 정규식으로 비교하지 않음";
            });
            ignoreCase.setEnabled(selected == DetectionRule.Field.BLE_ADVERTISED_NAME
                    || selected == DetectionRule.Field.CLASSIC_NAME || selected == DetectionRule.Field.LEGACY_NAME);
        };
        Runnable updateFields = () -> {
            int sourceIndex = source.getSelectedItemPosition();
            if (shownSource[0] == sourceIndex) return;
            boolean initial = shownSource[0] < 0;
            shownSource[0] = sourceIndex;
            available[0] = fieldsForEditor(rule, sources[sourceIndex]);
            field.setAdapter(new android.widget.ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                    java.util.Arrays.stream(available[0]).map(value -> value.label).toArray(String[]::new)));
            int selected = java.util.Arrays.asList(available[0]).indexOf(initial ? preferredField : rule.field());
            field.setSelection(Math.max(0, selected));
            updateAlert.run();
        };
        category.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) categoryError.setVisibility(View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        source.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateFields.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        field.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { updateAlert.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { updateAlert.run(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        updateFields.run();
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        var cancel = activity.settingsAction(R.id.settings_page_cancel, "취소", R.drawable.ic_close, false);
        var save = activity.settingsAction(R.id.settings_page_save, "저장", R.drawable.ic_save, true);
        cancel.setOnClickListener(view -> activity.closeSettingsPage());
        save.setOnClickListener(view -> {
            int categoryIndex = category.getSelectedItemPosition() - 1;
            if (categoryIndex < 0) {
                categoryError.setVisibility(View.VISIBLE);
                categoryError.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
                        categoryError.getWidth(), dp(48)), false);
                return;
            }
            String raw = input.getText() == null ? "" : input.getText().toString();
            if (raw.isEmpty()) { wrapper.setError("스캔 값을 입력하세요."); return; }
            try {
                DetectionRule.Source selectedSource = sources[source.getSelectedItemPosition()];
                DetectionRule.Field selectedField = available[0][field.getSelectedItemPosition()];
                boolean legacy = rule.evidenceLevel() == DetectionRule.EvidenceLevel.LEGACY
                        && selectedSource == rule.source() && selectedField == rule.field();
                DetectionRule updated = new DetectionRule(rule.id(), categories[categoryIndex],
                        selectedSource, selectedField,
                        raw, ignoreCase.isEnabled() && ignoreCase.isChecked(), alerts.isChecked(),
                        legacy ? DetectionRule.EvidenceLevel.LEGACY : DetectionRule.EvidenceLevel.USER,
                        rule.evidenceLevel() == DetectionRule.EvidenceLevel.LEGACY ? "" : rule.modelScope(), rule.sourceUrl());
                store.saveRule(updated);
            } catch (IllegalArgumentException invalid) {
                wrapper.setError("출처와 필드, 전체 값 형식을 확인하세요.");
                return;
            }
            activity.closeSettingsPage();
            reloadTargets();
            activity.preferencesChanged();
        });
        View page;
        if (existing) {
            var delete = activity.settingsAction(R.id.settings_page_delete, "삭제", R.drawable.ic_close, false);
            delete.setOnClickListener(view -> delete(rule));
            page = activity.settingsWithActions(scroll, delete, cancel, save);
        } else page = activity.settingsWithActions(scroll, cancel, save);
        saveEditor = () -> {
            Bundle result = new Bundle();
            try {
                result.putString("rule", rule.toJson().toString());
            } catch (org.json.JSONException error) {
                return null;
            }
            result.putBoolean("existing", existing);
            result.putInt("category", category.getSelectedItemPosition());
            result.putInt("source", source.getSelectedItemPosition());
            result.putString("field", available[0][Math.max(0, field.getSelectedItemPosition())].name());
            result.putString("value", input.getText() == null ? "" : input.getText().toString());
            result.putBoolean("ignoreCase", ignoreCase.isChecked());
            result.putBoolean("alerts", alerts.isChecked());
            return result;
        };
        activity.showSettingsPage(R.id.target_device_category, existing ? "스캔 대상 수정" : "스캔 대상 추가",
                page, () -> saveEditor = null);
    }

    Bundle savePageState() {
        Bundle result = new Bundle();
        result.putInt("filter", filter);
        result.putInt("category", CATEGORY_OPTIONS.indexOf(categoryFilter));
        result.putString("query", query);
        result.putString("builtin", openBuiltin);
        if (saveEditor != null) result.putBundle("editor", saveEditor.get());
        return result;
    }

    void restorePageState(Bundle state, boolean showBrowser) {
        if (state != null) {
            filter = state.getInt("filter", R.id.targets_all);
            categoryFilter = CATEGORY_OPTIONS.get(state.getInt("category", 0));
            query = state.getString("query", "");
        }
        if (showBrowser) show();
        if (state == null) return;
        Bundle editor = state.getBundle("editor");
        if (editor != null) {
            try {
                DetectionRule rule = DetectionRule.fromJson(new org.json.JSONObject(editor.getString("rule", "")));
                edit(rule, editor.getBoolean("existing"), editor);
            } catch (org.json.JSONException | IllegalArgumentException invalid) {
                // The current rules list remains available if an old draft cannot be restored.
            }
        } else if (showBrowser && state.getString("builtin") != null) {
            store.builtins().stream().filter(entry -> entry.label().equals(state.getString("builtin")))
                    .findFirst().ifPresent(this::showDetails);
        }
    }

    private void delete(DetectionRule rule) {
        new IconDialogBuilder(activity).setTitle("이 스캔 대상을 삭제할까요?")
                .setMessage(rule.category().label + " · " + rule.source().label + " · " + rule.field().label
                        + "\n" + rule.value() + "\n다음 스캔부터 적용됩니다.")
                .setNegativeButton("취소", null).setPositiveButton("삭제", (dialog, which) -> {
                    store.deleteRule(rule.id());
                    activity.closeSettingsPage();
                    reloadTargets();
                    activity.preferencesChanged();
                }).show();
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
            row.evidence.setText(target.builtin() ? target.device().evidenceLevel().label + " · 실기 검증 전\n"
                    + target.device().evidence().split("\n", 2)[0]
                    : target.rule().evidenceLevel().label + " · " + target.rule().category().label + "\n"
                    + (target.rule().alerts() ? "정확한 조건 일치 알림 사용" : "정보 표시 · 알림 꺼짐"));
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
