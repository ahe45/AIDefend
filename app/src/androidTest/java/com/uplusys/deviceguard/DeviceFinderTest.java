package com.uplusys.deviceguard;

import android.content.Intent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeviceFinderTest {
    @Test public void detailsOfferFinderAndMissingSignalIsNotShownAsLive() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        org.junit.Assume.assumeTrue("Complete required permission setup before feature UI tests",
                PermissionSetup.ready(instrumentation.getTargetContext()));
        ScannerActivity activity = (ScannerActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), ScannerActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ScanObservation target = new ScanObservation(new RadioScanner.Signal(RadioScanner.Mode.BLE,
                "02:00:00:00:00:02", "Finder test device", -50, Set.of(), Set.of(), Set.of()), List.of());
        try {
            instrumentation.waitForIdleSync();
            java.util.concurrent.atomic.AtomicBoolean splash = new java.util.concurrent.atomic.AtomicBoolean(true);
            long deadline = android.os.SystemClock.elapsedRealtime() + 5_000;
            do {
                instrumentation.runOnMainSync(() -> splash.set(activity.splashShowing()));
                if (!splash.get()) break;
                android.os.SystemClock.sleep(30);
            } while (android.os.SystemClock.elapsedRealtime() < deadline);
            assertFalse("Startup splash must finish before opening device details", splash.get());
            instrumentation.runOnMainSync(() -> activity.openObservation(target));
            instrumentation.getUiAutomation().waitForIdle(200, 5000);
            var root = instrumentation.getUiAutomation().getRootInActiveWindow();
            assertTrue(contains(root, "위치 찾기"));
            assertFalse(contains(root, "탐색 대상에 추가"));
            assertFalse(contains(root, "사진 확인"));
            assertFalse(contains(root, "신호 상세"));
            instrumentation.runOnMainSync(() -> {
                var toolbar = (androidx.appcompat.widget.Toolbar) activity.findViewById(R.id.toolbar);
                assertEquals(activity.getString(R.string.device_detail_title), toolbar.getTitle().toString());
                assertTrue(activity.findViewById(R.id.device_detail_find).performClick());
            });
            long dialogDeadline = android.os.SystemClock.elapsedRealtime() + 4_000;
            do {
                instrumentation.waitForIdleSync();
                root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (contains(root, "측정값 없음")) break;
                android.os.SystemClock.sleep(80);
            } while (android.os.SystemClock.elapsedRealtime() < dialogDeadline);
            assertTrue("The finder must show when a measurement is unavailable", contains(root, "측정값 없음"));
            assertTrue(contains(root, "신호를 수신해야 위치 찾기가 가능합니다."));
            assertFalse(contains(root, "실제 거리나 방향을 측정하는 기능은 아닙니다."));
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
        }
    }

    private static boolean contains(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().contains(text)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (contains(node.getChild(i), text)) return true;
        return false;
    }
}
