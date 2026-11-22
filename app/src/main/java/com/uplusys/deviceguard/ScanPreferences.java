package com.uplusys.deviceguard;

import android.content.Context;
import android.content.SharedPreferences;

final class ScanPreferences {
    static final long[] DURATIONS = {30_000, 60_000, 180_000, 300_000, 600_000, 0};
    static final String[] DURATION_LABELS = {"30초", "1분", "3분", "5분", "10분", "무제한"};
    static final int DEFAULT_RETENTION_DAYS = 30;
    static final int UNLIMITED_RETENTION = 0;
    private final SharedPreferences values;

    ScanPreferences(Context context) {
        values = context.getSharedPreferences("ScannerActivity", Context.MODE_PRIVATE);
        if (!values.getBoolean("multi_radio_default_v1", false)) {
            values.edit().putString("mode", RadioScanner.Mode.ALL.name())
                    .putBoolean("multi_radio_default_v1", true).apply();
        }
    }

    RadioScanner.Mode mode() {
        try {
            return RadioScanner.Mode.valueOf(values.getString("mode", "ALL"));
        } catch (IllegalArgumentException invalid) {
            return RadioScanner.Mode.ALL;
        }
    }

    void mode(RadioScanner.Mode mode) { values.edit().putString("mode", mode.name()).apply(); }
    int duration() { return Math.max(0, Math.min(DURATIONS.length - 1, values.getInt("duration", DURATIONS.length - 1))); }
    void duration(int value) { values.edit().putInt("duration", value).apply(); }
    boolean overlayAlerts() { return values.getBoolean("overlay_alerts", true); }
    void overlayAlerts(boolean value) { values.edit().putBoolean("overlay_alerts", value).apply(); }
    boolean alerts() { return values.getBoolean("candidate_alerts", true); }
    void alerts(boolean value) { values.edit().putBoolean("candidate_alerts", value).apply(); }
    boolean advanced() { return values.getBoolean("advanced", false); }
    void advanced(boolean value) { values.edit().putBoolean("advanced", value).apply(); }
    int theme() { return values.getInt("theme", 0); }
    void theme(int value) { values.edit().putInt("theme", value).apply(); }
    int retentionDays() { return values.getInt("history_retention_days", DEFAULT_RETENTION_DAYS); }
    void retentionDays(int days) { values.edit().putInt("history_retention_days", days).apply(); }
    boolean askedNotifications() { return values.getBoolean("asked_notifications", false); }
    void askedNotifications(boolean value) { values.edit().putBoolean("asked_notifications", value).apply(); }
}
