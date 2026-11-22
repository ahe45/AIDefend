package com.uplusys.deviceguard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

record SessionConfig(Set<DeviceCategory> categories, SavedTargets.Snapshot rules, RadioScanner.Mode mode,
                     List<RadioScanner.Mode> paths, boolean automatic, String ruleVersion, long createdAtElapsed) {
    static final String RULE_VERSION = "deviceguard-2026.11.22-v3";

    SessionConfig {
        if (categories.isEmpty() || categories.stream().anyMatch(category -> !category.selectable()))
            throw new IllegalArgumentException("스캔할 기기를 한 종류 이상 선택하세요");
        categories = Set.copyOf(categories);
        paths = List.copyOf(paths);
        if (paths.isEmpty() || paths.stream().anyMatch(path -> path == RadioScanner.Mode.ALL || path == RadioScanner.Mode.COMBINED))
            throw new IllegalArgumentException("실행할 스캔 경로 필요");
    }

    static SessionConfig create(Set<DeviceCategory> categories, SavedTargets.Snapshot rules,
                                RadioScanner.Mode manualMode, boolean automatic) {
        List<RadioScanner.Mode> paths = automatic ? automaticPaths(categories, rules) : pathsFor(manualMode);
        RadioScanner.Mode mode = paths.contains(RadioScanner.Mode.WIFI)
                ? paths.size() > 1 ? RadioScanner.Mode.ALL : RadioScanner.Mode.WIFI
                : paths.contains(RadioScanner.Mode.CLASSIC)
                ? paths.contains(RadioScanner.Mode.BLE) ? RadioScanner.Mode.COMBINED : RadioScanner.Mode.CLASSIC
                : RadioScanner.Mode.BLE;
        return new SessionConfig(categories, rules, mode, paths, automatic, RULE_VERSION, 0);
    }

    static List<RadioScanner.Mode> pathsFor(RadioScanner.Mode mode) {
        return switch (mode) {
            case ALL -> List.of(RadioScanner.Mode.BLE, RadioScanner.Mode.CLASSIC, RadioScanner.Mode.WIFI);
            case COMBINED -> List.of(RadioScanner.Mode.BLE, RadioScanner.Mode.CLASSIC);
            default -> List.of(mode);
        };
    }

    static List<RadioScanner.Mode> automaticPaths(Set<DeviceCategory> categories, SavedTargets.Snapshot rules) {
        Set<RadioScanner.Mode> needed = EnumSet.noneOf(RadioScanner.Mode.class);
        for (DeviceCategory category : categories) needed.addAll(category.defaultPaths);
        for (DeviceCatalog.Entry entry : DeviceCatalog.PUBLISHED_ENTRIES) {
            if (categories.contains(entry.category()) && !rules.deletedBuiltins().contains(entry.label())) {
                needed.addAll(entry.paths());
            }
        }
        for (DetectionRule rule : rules.rules()) {
            if (categories.contains(rule.category()) && rule.source() != DetectionRule.Source.ANY)
                needed.add(RadioScanner.Mode.valueOf(rule.source().name()));
        }
        List<RadioScanner.Mode> ordered = new ArrayList<>();
        if (categories.contains(DeviceCategory.CAMERA) && needed.contains(RadioScanner.Mode.WIFI)) ordered.add(RadioScanner.Mode.WIFI);
        for (RadioScanner.Mode path : List.of(RadioScanner.Mode.BLE, RadioScanner.Mode.CLASSIC, RadioScanner.Mode.WIFI)) {
            if (needed.contains(path) && !ordered.contains(path)) ordered.add(path);
        }
        return ordered;
    }

    String summary() { return "선택 " + categories.size() + "종 · " + paths.stream().map(path -> path.label).collect(Collectors.joining(" + ")); }
    String categorySummary() { return categories.stream().sorted().map(category -> category.label).collect(Collectors.joining(" · ")); }

    JSONObject toJson() throws JSONException {
        JSONArray selected = new JSONArray();
        for (DeviceCategory category : categories) selected.put(category.name());
        JSONArray routes = new JSONArray();
        for (RadioScanner.Mode path : paths) routes.put(path.name());
        return new JSONObject().put("categories", selected).put("rules", rules.toJson()).put("mode", mode.name())
                .put("paths", routes).put("automatic", automatic).put("ruleVersion", ruleVersion).put("createdAtElapsed", createdAtElapsed);
    }

    static SessionConfig fromJson(JSONObject row) throws JSONException {
        Set<DeviceCategory> categories = EnumSet.noneOf(DeviceCategory.class);
        JSONArray selected = row.getJSONArray("categories");
        for (int i = 0; i < selected.length(); i++) categories.add(DeviceCategory.valueOf(selected.getString(i)));
        List<RadioScanner.Mode> paths = new ArrayList<>();
        JSONArray routes = row.getJSONArray("paths");
        for (int i = 0; i < routes.length(); i++) paths.add(RadioScanner.Mode.valueOf(routes.getString(i)));
        return new SessionConfig(categories, SavedTargets.Snapshot.fromJson(row.getJSONObject("rules")),
                RadioScanner.Mode.valueOf(row.getString("mode")), paths, row.getBoolean("automatic"),
                row.getString("ruleVersion"), row.optLong("createdAtElapsed", 0));
    }
}
