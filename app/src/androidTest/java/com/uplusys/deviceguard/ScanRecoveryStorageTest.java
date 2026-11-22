package com.uplusys.deviceguard;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ScanRecoveryStorageTest {
    @Test public void persistsFrozenRequestAndClearsStoppedExpiredAndInvalidRequests() {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "recovery_test_" + UUID.randomUUID();
        Context isolated = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) {
                return super.getSharedPreferences(name, mode);
            }
        };
        try {
            ScanRecovery recovery = new ScanRecovery(isolated);
            SessionConfig frozen = frozenConfig();
            assertTrue(recovery.remember(frozen, 60_000, "frozen-session"));
            ScanRecovery.Request loaded = new ScanRecovery(isolated).load();
            assertNotNull(loaded);
            assertEquals("frozen-session", loaded.session());
            assertEquals(RadioScanner.Mode.ALL, loaded.mode());
            assertEquals(frozen, loaded.config());
            assertEquals(" ExactCamera ", loaded.config().rules().rules().get(0).value());
            assertTrue(loaded.config().rules().rules().get(0).alerts());
            assertTrue(loaded.remaining(SystemClock.elapsedRealtime()) <= 60_000);
            assertTrue(loaded.remaining(SystemClock.elapsedRealtime()) > 0);
            recovery.clear();
            assertNull(new ScanRecovery(isolated).load());

            assertTrue(recovery.remember(frozen, 60_000, "expired"));
            SharedPreferences values = isolated.getSharedPreferences("scan_recovery", Context.MODE_PRIVATE);
            assertTrue(values.edit().putLong("deadline", SystemClock.elapsedRealtime() - 1).commit());
            assertNull(recovery.load());
            assertFalse(values.contains("mode"));

            assertTrue(recovery.remember(frozen, 0, "invalid"));
            assertTrue(values.edit().putString("mode", "UNKNOWN").commit());
            assertNull(recovery.load());
        } finally {
            base.deleteSharedPreferences(name);
        }
    }

    @Test public void rejectsUnfrozenLegacyAndUnsupportedRuleVersionRequests() {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "recovery_version_test_" + UUID.randomUUID();
        Context isolated = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) {
                return super.getSharedPreferences(name, mode);
            }
        };
        try {
            ScanRecovery recovery = new ScanRecovery(isolated);
            SharedPreferences values = isolated.getSharedPreferences("scan_recovery", Context.MODE_PRIVATE);
            assertTrue(recovery.remember(RadioScanner.Mode.ALL, 60_000, "legacy-unfrozen"));
            assertFalse(values.contains("config"));
            assertNull(new ScanRecovery(isolated).load());
            assertFalse(values.contains("session"));

            SessionConfig frozen = frozenConfig();
            SessionConfig unsupported = new SessionConfig(frozen.categories(), frozen.rules(), frozen.mode(),
                    frozen.paths(), frozen.automatic(), "unsupported-rule-version", frozen.createdAtElapsed());
            assertTrue(recovery.remember(unsupported, 60_000, "unsupported-version"));
            assertTrue(values.contains("config"));
            assertNull(new ScanRecovery(isolated).load());
            assertFalse(values.contains("session"));

            assertTrue(recovery.remember(frozen, 60_000, "current-version"));
            assertEquals(frozen, new ScanRecovery(isolated).load().config());
        } finally {
            base.deleteSharedPreferences(name);
        }
    }

    private SessionConfig frozenConfig() {
        DetectionRule camera = new DetectionRule("camera-rule", DeviceCategory.CAMERA, DetectionRule.Source.WIFI,
                DetectionRule.Field.SSID, " ExactCamera ", false, true, DetectionRule.EvidenceLevel.USER,
                "사용자 검증 장비", "");
        SavedTargets.Snapshot rules = new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of(),
                Set.of("Rokid Glasses"), List.of(camera));
        return SessionConfig.create(Set.of(DeviceCategory.CAMERA, DeviceCategory.WATCH), rules, RadioScanner.Mode.ALL, false);
    }
}
