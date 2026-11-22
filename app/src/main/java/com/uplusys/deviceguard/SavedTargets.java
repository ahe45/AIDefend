package com.uplusys.deviceguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class SavedTargets {
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;

    SavedTargets(Context context) {
        preferences = context.getSharedPreferences("saved_targets", Context.MODE_PRIVATE);
    }

    Set<String> values(boolean uuid) { return valuesFor(key(uuid)); }
    Set<String> manufacturerIds() { return valuesFor("manufacturer_ids"); }

    private Set<String> valuesFor(String key) {
        return new LinkedHashSet<>(preferences.getStringSet(key, Set.of()));
    }

    void change(boolean uuid, String value, boolean add) {
        replace(key(uuid), add ? null : value, add ? value : null);
    }

    void changeManufacturer(String value, boolean add) {
        replace("manufacturer_ids", add ? null : value, add ? value : null);
    }

    List<DetectionRule> rules() {
        synchronized (LOCK) { return List.copyOf(readRules()); }
    }

    private List<DetectionRule> readRules() {
        String encoded = preferences.getString("typed_rules_v1", null);
        if (encoded == null) {
            List<DetectionRule> migrated = legacyRules(values(false), values(true), manufacturerIds());
            persist(migrated, preferences.edit());
            return migrated;
        }
        List<DetectionRule> result = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(encoded);
            for (int i = 0; i < rows.length(); i++) {
                try { result.add(DetectionRule.fromJson(rows.getJSONObject(i))); }
                catch (JSONException | IllegalArgumentException invalid) {
                    Log.w("DeviceGuard", "Unreadable saved rule at index " + i);
                }
            }
        } catch (JSONException invalid) {
            Log.w("DeviceGuard", "Unreadable typed rules, using legacy registrations");
            result.addAll(legacyRules(values(false), values(true), manufacturerIds()));
        }
        return result;
    }

    void saveRule(DetectionRule rule) {
        if (!rule.category().selectable()) throw new IllegalArgumentException("기기 종류를 선택하세요.");
        synchronized (LOCK) {
            List<DetectionRule> rules = readRules();
            DetectionRule previous = rules.stream().filter(value -> value.id().equals(rule.id())).findFirst().orElse(null);
            rules.removeIf(value -> value.id().equals(rule.id()));
            rules.add(rule);
            SharedPreferences.Editor editor = preferences.edit();
            removeLegacy(previous, editor);
            persist(rules, editor);
        }
    }

    void deleteRule(String id) {
        synchronized (LOCK) {
            List<DetectionRule> rules = readRules();
            DetectionRule previous = rules.stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
            rules.removeIf(value -> value.id().equals(id));
            SharedPreferences.Editor editor = preferences.edit();
            removeLegacy(previous, editor);
            persist(rules, editor);
        }
    }

    private void removeLegacy(DetectionRule rule, SharedPreferences.Editor editor) {
        if (rule == null || rule.evidenceLevel() != DetectionRule.EvidenceLevel.LEGACY) return;
        String key = switch (rule.field()) {
            case LEGACY_NAME -> "device_names";
            case LEGACY_UUID -> "service_uuids";
            case MANUFACTURER_ID -> "manufacturer_ids";
            default -> null;
        };
        if (key == null) return;
        Set<String> entries = valuesFor(key);
        entries.removeIf(value -> sameLegacyValue(rule.field(), value, rule.value()));
        editor.putStringSet(key, entries);
    }

    private void persist(List<DetectionRule> rules, SharedPreferences.Editor editor) {
        JSONArray rows = new JSONArray();
        try {
            for (DetectionRule rule : rules) rows.put(rule.toJson());
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("스캔 규칙을 저장할 수 없습니다", invalid);
        }
        editor.putString("typed_rules_v1", rows.toString()).putInt("rule_schema", 1).apply();
    }

    record Snapshot(Set<String> names, Set<String> uuids, Set<String> manufacturerIds,
                    Set<String> deletedBuiltins, List<DetectionRule> rules) {
        Snapshot {
            names = Set.copyOf(names);
            uuids = Set.copyOf(uuids);
            manufacturerIds = Set.copyOf(manufacturerIds);
            deletedBuiltins = Set.copyOf(deletedBuiltins);
            rules = List.copyOf(rules);
        }
        Snapshot(Set<String> names, Set<String> uuids, Set<String> manufacturerIds) {
            this(names, uuids, manufacturerIds, Set.of());
        }
        Snapshot(Set<String> names, Set<String> uuids, Set<String> manufacturerIds, Set<String> deletedBuiltins) {
            this(names, uuids, manufacturerIds, deletedBuiltins, legacyRules(names, uuids, manufacturerIds));
        }
        JSONObject toJson() throws JSONException {
            JSONArray rows = new JSONArray();
            for (DetectionRule rule : rules) rows.put(rule.toJson());
            return new JSONObject().put("names", array(names)).put("uuids", array(uuids))
                    .put("manufacturerIds", array(manufacturerIds)).put("deletedBuiltins", array(deletedBuiltins))
                    .put("rules", rows);
        }
        static Snapshot fromJson(JSONObject row) throws JSONException {
            List<DetectionRule> rules = new ArrayList<>();
            JSONArray entries = row.optJSONArray("rules");
            if (entries != null) for (int i = 0; i < entries.length(); i++)
                rules.add(DetectionRule.fromJson(entries.getJSONObject(i)));
            Set<String> names = strings(row.optJSONArray("names"));
            Set<String> uuids = strings(row.optJSONArray("uuids"));
            Set<String> companies = strings(row.optJSONArray("manufacturerIds"));
            return entries == null ? new Snapshot(names, uuids, companies, strings(row.optJSONArray("deletedBuiltins")))
                    : new Snapshot(names, uuids, companies, strings(row.optJSONArray("deletedBuiltins")), rules);
        }
        private static JSONArray array(Set<String> values) {
            JSONArray result = new JSONArray();
            for (String value : values) result.put(value);
            return result;
        }
        private static Set<String> strings(JSONArray array) throws JSONException {
            Set<String> result = new LinkedHashSet<>();
            if (array != null) for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
            return result;
        }
    }

    List<DeviceCatalog.Entry> builtins() {
        Set<String> deleted = valuesFor("deleted_builtin_targets");
        return DeviceCatalog.ENTRIES.stream().filter(entry -> !deleted.contains(entry.label()))
                .collect(java.util.stream.Collectors.toList());
    }

    void deleteBuiltin(DeviceCatalog.Entry entry) { replace("deleted_builtin_targets", null, entry.label()); }

    Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(values(false), values(true), manufacturerIds(),
                    valuesFor("deleted_builtin_targets"), rules());
        }
    }

    void replace(String key, String previous, String value) {
        synchronized (LOCK) {
            Set<String> entries = valuesFor(key);
            if (previous != null) entries.remove(previous);
            if (value != null) entries.add(value);
            if (key.equals("deleted_builtin_targets")) {
                preferences.edit().putStringSet(key, entries).apply();
                return;
            }
            List<DetectionRule> rules = readRules();
            DetectionRule.Field field = switch (key) {
                case "device_names" -> DetectionRule.Field.LEGACY_NAME;
                case "service_uuids" -> DetectionRule.Field.LEGACY_UUID;
                case "manufacturer_ids" -> DetectionRule.Field.MANUFACTURER_ID;
                default -> throw new IllegalArgumentException("Unknown registration field");
            };
            if (previous != null) rules.removeIf(rule -> rule.evidenceLevel() == DetectionRule.EvidenceLevel.LEGACY
                    && rule.field() == field && sameLegacyValue(field, rule.value(), previous));
            if (value != null && rules.stream().noneMatch(rule -> rule.field() == field && sameLegacyValue(field, rule.value(), value)))
                rules.add(legacyRule(key, field, value));
            persist(rules, preferences.edit().putStringSet(key, entries));
        }
    }

    private static List<DetectionRule> legacyRules(Set<String> names, Set<String> uuids, Set<String> manufacturers) {
        List<DetectionRule> rules = new ArrayList<>();
        for (String value : names) addLegacy(rules, "device_names", DetectionRule.Field.LEGACY_NAME, value);
        for (String value : uuids) addLegacy(rules, "service_uuids", DetectionRule.Field.LEGACY_UUID, value);
        for (String value : manufacturers) addLegacy(rules, "manufacturer_ids", DetectionRule.Field.MANUFACTURER_ID, value);
        return rules;
    }

    private static boolean sameLegacyValue(DetectionRule.Field field, String left, String right) {
        try { return DetectionRule.normalizeValue(field, left).equalsIgnoreCase(DetectionRule.normalizeValue(field, right)); }
        catch (IllegalArgumentException invalid) { return left.equalsIgnoreCase(right); }
    }

    private static void addLegacy(List<DetectionRule> rules, String key, DetectionRule.Field field, String value) {
        try { rules.add(legacyRule(key, field, value)); }
        catch (IllegalArgumentException invalid) { Log.w("DeviceGuard", "Invalid legacy registration retained in original storage"); }
    }

    private static DetectionRule legacyRule(String key, DetectionRule.Field field, String value) {
        return new DetectionRule(UUID.nameUUIDFromBytes((key + "/" + value).getBytes(StandardCharsets.UTF_8)).toString(),
                DeviceCategory.CUSTOM, DetectionRule.Source.ANY, field, value, true, false,
                DetectionRule.EvidenceLevel.LEGACY, "출처 미지정 · 기존 등록", "");
    }

    static String normalizeUuid(String value) {
        String input = value.trim();
        if (!input.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid UUID");
        return UUID.fromString(input).toString();
    }

    static String normalizeManufacturer(String value) {
        String input = value.trim().replaceFirst("(?i)^0x", "");
        if (!input.matches("(?i)[0-9a-f]{4}")) throw new IllegalArgumentException("Invalid company ID");
        return String.format(java.util.Locale.ROOT, "0x%04X", Integer.parseInt(input, 16));
    }

    private String key(boolean uuid) { return uuid ? "service_uuids" : "device_names"; }
}
