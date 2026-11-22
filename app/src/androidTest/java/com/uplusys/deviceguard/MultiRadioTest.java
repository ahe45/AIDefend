package com.uplusys.deviceguard;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MultiRadioTest {
    @Test public void upgradesExistingDefaultOnceAndKeepsLaterChoice() {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "multi_radio_test_" + UUID.randomUUID();
        Context isolated = new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) {
                return super.getSharedPreferences(name, mode);
            }
        };
        try {
            isolated.getSharedPreferences("ScannerActivity", Context.MODE_PRIVATE).edit()
                    .putString("mode", "BLE").putInt("duration", 2).putBoolean("candidate_alerts", false).commit();
            ScanPreferences migrated = new ScanPreferences(isolated);
            assertEquals(RadioScanner.Mode.ALL, migrated.mode());
            assertEquals(2, migrated.duration());
            assertFalse(migrated.alerts());
            migrated.mode(RadioScanner.Mode.CLASSIC);
            assertEquals(RadioScanner.Mode.CLASSIC, new ScanPreferences(isolated).mode());
        } finally {
            base.deleteSharedPreferences(name);
        }
    }

    @Test public void unavailableRadiosContinueUntilDeadlineAndStopCancelsCallbacks() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context base = instrumentation.getTargetContext();
        Context unavailable = new ContextWrapper(base) {
            @Override public Object getSystemService(String name) {
                if (Context.BLUETOOTH_SERVICE.equals(name) || Context.WIFI_SERVICE.equals(name)) return null;
                return super.getSystemService(name);
            }
            @Override public Context getApplicationContext() { return this; }
        };
        List<RadioScanner.Mode> phases = new ArrayList<>();
        CountDownLatch ended = new CountDownLatch(1);
        AtomicInteger endings = new AtomicInteger();
        AtomicReference<RadioScanner.Outcome> outcome = new AtomicReference<>();
        RadioScanner scanner = new RadioScanner(unavailable, new RadioScanner.Listener() {
            @Override public void status(RadioScanner.Mode phase, String text) { phases.add(phase); }
            @Override public void found(RadioScanner.Signal signal) { fail("Radios are unavailable"); }
            @Override public void ended(RadioScanner.Outcome value, String message) {
                outcome.set(value);
                endings.incrementAndGet();
                ended.countDown();
            }
        });
        SavedTargets.Snapshot targets = new SavedTargets.Snapshot(Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet());
        try {
            instrumentation.runOnMainSync(() -> {
                scanner.start(RadioScanner.Mode.ALL, 150, targets);
                assertTrue(scanner.running());
            });
            assertTrue(ended.await(3, TimeUnit.SECONDS));
            assertEquals(RadioScanner.Outcome.COMPLETED, outcome.get());
            assertTrue(phases.contains(RadioScanner.Mode.WIFI));
            instrumentation.runOnMainSync(() -> {
                assertFalse(scanner.running());
                scanner.start(RadioScanner.Mode.ALL, 150, targets);
                scanner.stop(RadioScanner.Outcome.STOPPED, "test stop");
            });
            android.os.SystemClock.sleep(250);
            instrumentation.runOnMainSync(() -> assertFalse(scanner.running()));
            assertEquals(2, endings.get());
            assertEquals(RadioScanner.Outcome.STOPPED, outcome.get());
        } finally {
            instrumentation.runOnMainSync(() -> scanner.stop(RadioScanner.Outcome.STOPPED, "cleanup"));
        }
    }
}
