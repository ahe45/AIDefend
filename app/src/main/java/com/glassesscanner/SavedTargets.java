package com.glassesscanner;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

final class SavedTargets {
    private final SharedPreferences preferences;

    SavedTargets(Context context) {
        preferences = context.getSharedPreferences("saved_targets", Context.MODE_PRIVATE);
    }

    Set<String> values(boolean uuid) {
        return new LinkedHashSet<>(preferences.getStringSet(key(uuid), new LinkedHashSet<>()));
    }

    void change(boolean uuid, String value, boolean add) {
        Set<String> entries = values(uuid);
        if (add) entries.add(value);
        else entries.remove(value);
        preferences.edit().putStringSet(key(uuid), entries).apply();
    }

    static String normalizeUuid(String value) {
        String input = value.trim();
        if (!input.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid UUID");
        }
        return UUID.fromString(input).toString();
    }

    Set<String> manufacturerIds() {
        return new LinkedHashSet<>(preferences.getStringSet("manufacturer_ids", new LinkedHashSet<>()));
    }

    record Snapshot(Set<String> names, Set<String> uuids, Set<String> manufacturerIds, Set<String> deletedBuiltins) {
        Snapshot(Set<String> names, Set<String> uuids, Set<String> manufacturerIds) {
            this(names, uuids, manufacturerIds, Set.of());
        }
    }

    java.util.List<DeviceCatalog.Entry> builtins() {
        Set<String> deleted = preferences.getStringSet("deleted_builtin_targets", Set.of());
        return DeviceCatalog.ENTRIES.stream().filter(entry -> !deleted.contains(entry.label()))
                .collect(java.util.stream.Collectors.toList());
    }

    void deleteBuiltin(DeviceCatalog.Entry entry) {
        replace("deleted_builtin_targets", null, entry.label());
    }

    Snapshot snapshot() {
        return new Snapshot(values(false), values(true), manufacturerIds(),
                new LinkedHashSet<>(preferences.getStringSet("deleted_builtin_targets", Set.of())));
    }

    void replace(String key, String previous, String value) {
        Set<String> entries = new LinkedHashSet<>(preferences.getStringSet(key, new LinkedHashSet<>()));
        if (previous != null) entries.remove(previous);
        if (value != null) entries.add(value);
        preferences.edit().putStringSet(key, entries).apply();
    }

    void changeManufacturer(String value, boolean add) {
        Set<String> entries = manufacturerIds();
        if (add) entries.add(value);
        else entries.remove(value);
        preferences.edit().putStringSet("manufacturer_ids", entries).apply();
    }

    static String normalizeManufacturer(String value) {
        String input = value.trim().replaceFirst("(?i)^0x", "");
        if (!input.matches("(?i)[0-9a-f]{4}")) {
            throw new IllegalArgumentException("Invalid company ID");
        }
        return String.format(java.util.Locale.ROOT, "0x%04X", Integer.parseInt(input, 16));
    }

    private String key(boolean uuid) {
        return uuid ? "service_uuids" : "device_names";
    }
}

