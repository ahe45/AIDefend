package com.glassesscanner;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ScanRecoveryStorageTest {
    @Test public void persistsRequestAndClearsStoppedExpiredAndInvalidRequests() {
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
            assertTrue(recovery.remember(RadioScanner.Mode.ALL, 60_000, "old-session"));
            ScanRecovery.Request loaded = new ScanRecovery(isolated).load();
            assertNotNull(loaded);
            assertEquals("old-session", loaded.session());
            assertEquals(RadioScanner.Mode.ALL, loaded.mode());
            assertTrue(loaded.remaining(SystemClock.elapsedRealtime()) <= 60_000);
            assertTrue(loaded.remaining(SystemClock.elapsedRealtime()) > 0);
            recovery.clear();
            assertNull(new ScanRecovery(isolated).load());

            assertTrue(recovery.remember(RadioScanner.Mode.BLE, 60_000, "expired"));
            SharedPreferences values = isolated.getSharedPreferences("scan_recovery", Context.MODE_PRIVATE);
            assertTrue(values.edit().putLong("deadline", SystemClock.elapsedRealtime() - 1).commit());
            assertNull(recovery.load());
            assertFalse(values.contains("mode"));

            assertTrue(recovery.remember(RadioScanner.Mode.ALL, 0, "invalid"));
            assertTrue(values.edit().putString("mode", "UNKNOWN").commit());
            assertNull(recovery.load());
        } finally {
            base.deleteSharedPreferences(name);
        }
    }
}
