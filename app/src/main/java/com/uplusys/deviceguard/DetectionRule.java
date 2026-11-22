package com.uplusys.deviceguard;

import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

record DetectionRule(String id, DeviceCategory category, Source source, Field field, String value,
                     boolean ignoreCase, boolean alerts, EvidenceLevel evidenceLevel,
                     String modelScope, String sourceUrl) {
    enum Source {
        ANY("출처 미지정"), BLE("BLE"), CLASSIC("Bluetooth Classic"), WIFI("Wi-Fi AP");
        final String label;
        Source(String label) { this.label = label; }
        boolean accepts(RadioScanner.Mode mode) { return this == ANY || name().equals(mode.name()); }
    }

    enum Field {
        LEGACY_NAME("기존 이름 · 출처 미지정"), BLE_ADVERTISED_NAME("BLE 광고 이름"),
        DEVICE_NAME("Bluetooth 캐시 이름"), CLASSIC_NAME("Classic 응답 이름"), SSID("Wi-Fi SSID"),
        ADVERTISED_UUID("BLE 광고 서비스 UUID"), SERVICE_DATA_KEY("BLE 서비스 데이터 키"),
        LEGACY_UUID("기존 UUID · 광고/서비스 데이터"), MANUFACTURER_ID("BLE 제조사 ID");
        final String label;
        Field(String label) { this.label = label; }
    }

    enum EvidenceLevel {
        MEASURED("실측 검증"), OFFICIAL_TECHNICAL("공식 기술 자료"),
        OFFICIAL_PRODUCT("공식 제품·연결 정보"), USER("사용자 등록"), LEGACY("기존 규칙"), CLUE("보조 단서");
        final String label;
        EvidenceLevel(String label) { this.label = label; }
    }

    DetectionRule {
        if (id == null || id.trim().isEmpty() || value == null || value.isEmpty()) throw new IllegalArgumentException("규칙 ID와 값 필요");
        // CUSTOM remains readable for previously saved rules without a device type.
        if (category == null || category == DeviceCategory.UNKNOWN || source == null || field == null || evidenceLevel == null)
            throw new IllegalArgumentException("유형과 출처 필요");
        if (field == Field.SSID && source != Source.WIFI) throw new IllegalArgumentException("SSID는 Wi-Fi AP 출처 필요");
        if ((field == Field.BLE_ADVERTISED_NAME || field == Field.ADVERTISED_UUID
                || field == Field.SERVICE_DATA_KEY || field == Field.MANUFACTURER_ID) && source != Source.BLE
                && !(source == Source.ANY && evidenceLevel == EvidenceLevel.LEGACY))
            throw new IllegalArgumentException("BLE 필드는 BLE 출처 필요");
        if (field == Field.CLASSIC_NAME && source != Source.CLASSIC) throw new IllegalArgumentException("Classic 출처 필요");
        if (field == Field.DEVICE_NAME && source == Source.WIFI) throw new IllegalArgumentException("Bluetooth 출처 필요");
        value = normalizeValue(field, value);
        if (field == Field.SSID) ignoreCase = false;
        if (field == Field.MANUFACTURER_ID || field == Field.DEVICE_NAME || field == Field.LEGACY_NAME
                || field == Field.LEGACY_UUID || sharedUuid(field, value)
                || evidenceLevel == EvidenceLevel.LEGACY) alerts = false;
        modelScope = modelScope == null ? "" : modelScope;
        sourceUrl = sourceUrl == null ? "" : sourceUrl;
    }

    static String normalizeValue(Field field, String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("값 필요");
        return switch (field) {
            case ADVERTISED_UUID, SERVICE_DATA_KEY, LEGACY_UUID -> SavedTargets.normalizeUuid(value);
            case MANUFACTURER_ID -> SavedTargets.normalizeManufacturer(value);
            case SSID -> value;
            case LEGACY_NAME -> {
                String text = value.trim();
                if (text.isEmpty()) throw new IllegalArgumentException("값 필요");
                yield text;
            }
            default -> {
                if (value.trim().isEmpty()) throw new IllegalArgumentException("값 필요");
                yield value;
            }
        };
    }

    boolean matches(RadioScanner.Signal signal) {
        if (!source.accepts(signal.source())) return false;
        return switch (field) {
            case LEGACY_NAME -> same(signal.name());
            case BLE_ADVERTISED_NAME -> same(signal.advertisedName());
            case DEVICE_NAME -> same(signal.cachedName());
            case CLASSIC_NAME -> signal.source() == RadioScanner.Mode.CLASSIC
                    && signal.nameOrigin() == RadioScanner.NameOrigin.CLASSIC_RESPONSE && same(signal.name());
            case SSID -> signal.source() == RadioScanner.Mode.WIFI && value.equals(signal.name());
            case ADVERTISED_UUID -> signal.advertisedUuids().contains(value);
            case SERVICE_DATA_KEY -> signal.serviceDataKeys().contains(value);
            case LEGACY_UUID -> signal.advertisedUuids().contains(value) || signal.serviceDataKeys().contains(value);
            case MANUFACTURER_ID -> signal.companies().stream().anyMatch(id -> value.equals(String.format(Locale.ROOT, "0x%04X", id)));
        };
    }

    private boolean same(String actual) {
        if (actual == null) return false;
        String compared = field == Field.LEGACY_NAME ? actual.trim() : actual;
        return ignoreCase ? value.equalsIgnoreCase(compared) : value.equals(compared);
    }

    boolean preciseAlert() {
        return alerts && (evidenceLevel == EvidenceLevel.USER || evidenceLevel == EvidenceLevel.MEASURED)
                && field != Field.MANUFACTURER_ID
                && field != Field.DEVICE_NAME && field != Field.LEGACY_NAME && field != Field.LEGACY_UUID
                && !sharedUuid(field, value);
    }

    boolean classifying() {
        return field != Field.MANUFACTURER_ID && field != Field.DEVICE_NAME && !sharedUuid(field, value);
    }

    private static boolean sharedUuid(Field field, String value) {
        if (field != Field.ADVERTISED_UUID && field != Field.SERVICE_DATA_KEY && field != Field.LEGACY_UUID) return false;
        // Generic GATT service identifiers and shared protocol/family identifiers are detail-only clues.
        return value.matches("000018[0-9a-f]{2}-0000-1000-8000-00805f9b34fb")
                || java.util.Set.of(DeviceCatalog.G1_UART_UUID, DeviceCatalog.FRAME_UUID,
                "0000fe2c-0000-1000-8000-00805f9b34fb", "0000fdf0-0000-1000-8000-00805f9b34fb").contains(value);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("category", category.name()).put("source", source.name())
                .put("field", field.name()).put("value", value).put("ignoreCase", ignoreCase).put("alerts", alerts)
                .put("evidenceLevel", evidenceLevel.name()).put("modelScope", modelScope).put("sourceUrl", sourceUrl)
                .put("condition", "EXACT").put("ruleVersion", SessionConfig.RULE_VERSION);
    }

    static DetectionRule fromJson(JSONObject row) throws JSONException {
        if (!"EXACT".equals(row.optString("condition", "EXACT")))
            throw new IllegalArgumentException("지원하지 않는 일치 조건");
        return new DetectionRule(row.getString("id"), DeviceCategory.valueOf(row.getString("category")),
                Source.valueOf(row.getString("source")), Field.valueOf(row.getString("field")), row.getString("value"),
                row.optBoolean("ignoreCase", false), row.optBoolean("alerts", false),
                EvidenceLevel.valueOf(row.optString("evidenceLevel", "USER")),
                row.optString("modelScope"), row.optString("sourceUrl"));
    }
}
