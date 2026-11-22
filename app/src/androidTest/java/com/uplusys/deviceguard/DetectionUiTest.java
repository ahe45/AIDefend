package com.uplusys.deviceguard;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DetectionUiTest {
    @Test public void listsShowEvidenceAndWarningsShowSummary() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var automation = instrumentation.getUiAutomation();
        var context = instrumentation.getTargetContext();
        org.junit.Assume.assumeTrue(PermissionSetup.ready(context));
        ScannerActivity activity = (ScannerActivity) instrumentation.startActivitySync(
                new Intent(context, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ScanScreen[] screen = new ScanScreen[1];
        CandidateOverlay overlay = new CandidateOverlay(context);
        try {
            instrumentation.waitForIdleSync();
            long observed = SystemClock.elapsedRealtime();
            AtomicBoolean splash = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> splash.set(activity.splashShowing()));
            assertTrue("Brand image must be visible on launch", splash.get());
            screenshot(instrumentation, "splash-phone.png");
            do {
                SystemClock.sleep(30);
                instrumentation.runOnMainSync(() -> splash.set(activity.splashShowing()));
            } while (splash.get() && SystemClock.elapsedRealtime() - observed < 4_000);
            assertFalse("Splash must transition to app content", splash.get());
            RadioScanner.Signal signal = new RadioScanner.Signal(RadioScanner.Mode.BLE, "02:00:00:00:00:05",
                    "Glasses_4097", -58, Set.of(0x03C2), Set.of(DeviceCatalog.HEYCYAN_UUID), Set.of());
            ScanObservation row = new ScanObservation(signal,
                    ScanService.match(signal, new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of())));
            assertTrue("Fresh built-in device matches can produce a warning",
                    DeviceMatcher.alertEligible(row.matches()));
            ScanViewModel model = new ScanViewModel();
            model.scan = new ScanService.Snapshot("ui-evidence", ScanService.State.COMPLETED,
                    RadioScanner.Mode.BLE, RadioScanner.Mode.BLE, "테스트 화면", 0, 0, false, List.of(row), 1, false);
            instrumentation.runOnMainSync(() -> {
                screen[0] = new ScanScreen(activity, model, new ScanPreferences(activity));
                screen[0].render();
            });
            instrumentation.waitForIdleSync();
            automation.waitForIdle(300, 5000);
            instrumentation.runOnMainSync(() -> {
                RecyclerView list = activity.findViewById(R.id.results);
                var holder = list.findViewHolderForAdapterPosition(0);
                assertNotNull(holder);
                assertEquals("Glasses_4097", ((TextView) holder.itemView.findViewById(R.id.result_title)).getText().toString());
                assertEquals("스마트글래스 의심 기기", ((TextView) holder.itemView.findViewById(R.id.result_status)).getText().toString());
                assertEquals("-58 dBm", ((TextView) holder.itemView.findViewById(R.id.result_signal)).getText().toString());
                var badges = (com.google.android.material.chip.ChipGroup) holder.itemView.findViewById(R.id.result_evidence_badges);
                assertEquals(3, badges.getChildCount());
            });
            screenshot(instrumentation, "evidence-list-phone.png");
            instrumentation.runOnMainSync(() -> screen[0].showObservation(row));
            instrumentation.waitForIdleSync();
            automation.waitForIdle(300, 5000);
            var root = automation.getRootInActiveWindow();
            assertNotNull(root);
            assertFalse(root.findAccessibilityNodeInfosByText("수신값").isEmpty());
            assertFalse(root.findAccessibilityNodeInfosByText("일치 기준").isEmpty());
            assertFalse(root.findAccessibilityNodeInfosByText(DeviceCatalog.HEYCYAN_UUID).isEmpty());
            screenshot(instrumentation, "evidence-detail-phone.png");
            instrumentation.runOnMainSync(() -> {
                screen[0].close();
                assertTrue(activity.showCandidateWarning(row));
            });
            instrumentation.waitForIdleSync();
            automation.waitForIdle(300, 5000);
            root = automation.getRootInActiveWindow();
            assertNotNull(root);
            String name = row.name();
            String match = DetectionText.targets(row);
            assertWarningSummary(root, name, match);
            screenshot(instrumentation, "evidence-warning-phone.png");
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            instrumentation.runOnMainSync(() -> overlay.show(row,
                    new Intent(context, ScannerActivity.class)));
            instrumentation.waitForIdleSync();
            automation.waitForIdle(300, 5000);
            var serviceInfo = automation.getServiceInfo();
            serviceInfo.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(serviceInfo);
            var overlayWindow = automation.getWindows().stream().map(android.view.accessibility.AccessibilityWindowInfo::getRoot)
                    .filter(java.util.Objects::nonNull)
                    .filter(window -> !window.findAccessibilityNodeInfosByText(
                            activity.getString(R.string.candidate_warning_title)).isEmpty())
                    .findFirst().orElseThrow();
            assertWarningSummary(overlayWindow, name, match);
            screenshot(instrumentation, "evidence-overlay-phone.png");
        } finally {
            instrumentation.runOnMainSync(() -> {
                overlay.dismiss();
                if (screen[0] != null) screen[0].close();
                activity.finish();
            });
        }
    }

    private void assertWarningSummary(android.view.accessibility.AccessibilityNodeInfo root, String name, String match) {
        for (String text : List.of("기기명", name, "일치 항목", match)) {
            assertTrue(root.findAccessibilityNodeInfosByText(text).stream()
                    .anyMatch(node -> android.text.TextUtils.equals(text, node.getText())));
        }
        assertFalse(root.findAccessibilityNodeInfosByText("상세 보기").isEmpty());
        assertTrue(root.findAccessibilityNodeInfosByText("스캔 근거").isEmpty());
        assertTrue(root.findAccessibilityNodeInfosByText("위치 찾기").isEmpty());
        assertTrue(root.findAccessibilityNodeInfosByText("확인").isEmpty());
    }

    private void screenshot(Instrumentation instrumentation, String name) throws Exception {
        File directory = instrumentation.getTargetContext().getExternalFilesDir("verification");
        assertNotNull(directory);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            bitmap.recycle();
        }
    }
}
