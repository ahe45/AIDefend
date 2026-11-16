package com.glassesscanner;

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
            instrumentation.runOnMainSync(() -> activity.openObservation(target));
            instrumentation.getUiAutomation().waitForIdle(200, 5000);
            var root = instrumentation.getUiAutomation().getRootInActiveWindow();
            assertTrue(contains(root, "위치 찾기"));
            assertFalse(contains(root, "탐색 대상에 추가"));
            assertFalse(contains(root, "사진 확인"));
            assertFalse(contains(root, "신호 상세"));
            instrumentation.runOnMainSync(() -> activity.findDevice(target));
            instrumentation.getUiAutomation().waitForIdle(200, 5000);
            root = instrumentation.getUiAutomation().getRootInActiveWindow();
            assertTrue(contains(root, "측정값 없음"));
            assertTrue(contains(root, "실제 거리나 방향을 측정하는 기능은 아닙니다."));
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
