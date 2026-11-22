package com.uplusys.deviceguard;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

final class DetectionSettings {
    private final SharedPreferences preferences;

    DetectionSettings(Context context) { preferences = context.getSharedPreferences("detection_settings", Context.MODE_PRIVATE); }

    Set<DeviceCategory> selected() {
        Set<String> stored = preferences.getStringSet("categories", null);
        if (stored == null) return DeviceCategory.defaults();
        Set<DeviceCategory> result = EnumSet.noneOf(DeviceCategory.class);
        for (DeviceCategory category : DeviceCategory.values()) {
            if (category.selectable() && stored.contains(category.name())) result.add(category);
        }
        return result.isEmpty() ? DeviceCategory.defaults() : Set.copyOf(result);
    }

    void apply(Set<DeviceCategory> categories) {
        if (categories.isEmpty() || categories.stream().anyMatch(category -> !category.selectable()))
            throw new IllegalArgumentException("스캔할 기기를 한 종류 이상 선택하세요");
        Set<String> names = new HashSet<>();
        for (DeviceCategory category : categories) names.add(category.name());
        preferences.edit().putStringSet("categories", names).apply();
    }

    boolean automatic() { return preferences.getBoolean("automatic", true); }
    void automatic(boolean enabled) { preferences.edit().putBoolean("automatic", enabled).apply(); }

    SessionConfig snapshot(SavedTargets.Snapshot rules, RadioScanner.Mode manualMode) {
        return SessionConfig.create(selected(), rules, manualMode, automatic());
    }
}
