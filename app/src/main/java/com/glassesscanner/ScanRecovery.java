package com.glassesscanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/** Durable intent to resume a user-started foreground scan in the current boot. */
final class ScanRecovery {
    record Request(RadioScanner.Mode mode, String session, long started, long deadline, int boot) {
        boolean resumable(long now, int currentBoot) {
            return boot == currentBoot && now >= started && (deadline == 0 || now < deadline);
        }
        long remaining(long now) { return deadline == 0 ? 0 : Math.max(0, deadline - now); }
    }

    private final Context context;
    private final SharedPreferences values;

    ScanRecovery(Context context) {
        this.context = context.getApplicationContext();
        values = this.context.getSharedPreferences("scan_recovery", Context.MODE_PRIVATE);
    }

    boolean remember(RadioScanner.Mode mode, long duration, String session) {
        long now = SystemClock.elapsedRealtime();
        // Persist before starting the scan so a killed process can resume the same request.
        return values.edit().putString("mode", mode.name()).putString("session", session)
                .putLong("started", now).putLong("deadline", duration == 0 ? 0 : now + duration)
                .putInt("boot", boot()).commit();
    }

    Request load() {
        String mode = values.getString("mode", null);
        String session = values.getString("session", null);
        if (mode == null || session == null) return null;
        try {
            Request request = new Request(RadioScanner.Mode.valueOf(mode), session,
                    values.getLong("started", 0), values.getLong("deadline", 0), values.getInt("boot", -1));
            if (request.resumable(SystemClock.elapsedRealtime(), boot())) return request;
        } catch (IllegalArgumentException invalid) {
            // A stale or unsupported mode must not revive an unrelated scan.
        }
        clear();
        return null;
    }

    void clear() {
        // User stop must be durable before Android can restart the service.
        values.edit().clear().commit();
    }

    private int boot() { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
}
