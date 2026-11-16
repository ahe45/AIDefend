package com.glassesscanner;

import android.content.Intent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CandidateWarningTest {
    @Test public void updatesForegroundWarningAndRejectsBackgroundDialog() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        org.junit.Assume.assumeTrue("Complete required permission setup before feature UI tests",
                PermissionSetup.ready(instrumentation.getTargetContext()));
        var context = instrumentation.getTargetContext();
        ScannerActivity activity = (ScannerActivity) instrumentation.startActivitySync(
                new Intent(context, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.waitForIdleSync();
            instrumentation.getUiAutomation().waitForIdle(200, 5000);
            AtomicBoolean shown = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> shown.set(activity.showCandidateWarning(row("First test candidate"))));
            assertTrue(shown.get());
            instrumentation.waitForIdleSync();
            instrumentation.getUiAutomation().waitForIdle(200, 5000);
            assertTrue(contains(instrumentation.getUiAutomation().getRootInActiveWindow(), "First test candidate"));
            instrumentation.runOnMainSync(() -> shown.set(activity.showCandidateWarning(row("Latest test candidate"))));
            instrumentation.waitForIdleSync();
            instrumentation.getUiAutomation().waitForIdle(200, 5000);
            assertTrue(shown.get());
            assertTrue(contains(instrumentation.getUiAutomation().getRootInActiveWindow(), "Latest test candidate"));
            assertFalse(contains(instrumentation.getUiAutomation().getRootInActiveWindow(), "First test candidate"));
            instrumentation.runOnMainSync(() -> activity.moveTaskToBack(true));
            instrumentation.waitForIdleSync();
            instrumentation.getUiAutomation().waitForIdle(200, 5000);
            instrumentation.runOnMainSync(() -> shown.set(activity.showCandidateWarning(row("Background test candidate"))));
            assertFalse(shown.get());
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            instrumentation.getUiAutomation().waitForIdle(200, 5000);
        }
    }

    private static ScanObservation row(String name) {
        return new ScanObservation(new RadioScanner.Signal(RadioScanner.Mode.BLE,
                "02:00:00:00:00:01", name, -45, Set.of(), Set.of(), Set.of()),
                List.of(new GlassesMatcher.Match("Test candidate", "Test evidence", 3)));
    }

    private static boolean contains(AccessibilityNodeInfo node, String value) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().contains(value)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (contains(node.getChild(i), value)) return true;
        }
        return false;
    }
}
