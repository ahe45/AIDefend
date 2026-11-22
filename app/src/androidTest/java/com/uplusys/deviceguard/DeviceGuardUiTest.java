package com.uplusys.deviceguard;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeviceGuardUiTest {
    @Test public void bottomMenuUsesGestureAreaAndKeepsObstructionsClear() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 30
                && PermissionSetup.ready(instrumentation.getTargetContext()));
        ScannerActivity activity = launch(instrumentation);
        try {
            instrumentation.runOnMainSync(() -> {
                View frame = activity.findViewById(R.id.scanner_frame);
                View root = activity.findViewById(R.id.root);
                View menu = activity.findViewById(R.id.navigation);
                assertEquals(View.VISIBLE, menu.getVisibility());
                dispatchBottomInsets(frame, 24, 0, 32, 0);
                assertEquals(0, root.getPaddingBottom());
                dispatchBottomInsets(frame, 48, 48, 48, 0);
                assertEquals(48, root.getPaddingBottom());
                dispatchBottomInsets(frame, 24, 0, 32, 240);
                assertEquals(240, root.getPaddingBottom());
                activity.findViewById(R.id.select_categories).performClick();
                assertEquals(View.GONE, menu.getVisibility());
                dispatchBottomInsets(frame, 24, 0, 32, 0);
                assertEquals(32, root.getPaddingBottom());
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    private static void dispatchBottomInsets(View frame, int navigation, int tappable, int gestures, int ime) {
        ViewCompat.dispatchApplyWindowInsets(frame, new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigation))
                .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, 0, 0, tappable))
                .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, gestures))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .build());
    }

    @Test public void showsInstalledVersionOnSplashAndAtEndOfAppSettings() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        String versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        String displayVersion = "ver_" + versionName;
        ScannerActivity activity = (ScannerActivity) instrumentation.startActivitySync(
                new Intent(context, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                TextView version = activity.findViewById(R.id.splash_version);
                assertTrue(version.isShown());
                assertEquals(displayVersion, version.getText().toString());
            });
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            var splash = new java.util.concurrent.atomic.AtomicBoolean(true);
            while (splash.get() && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync(() -> splash.set(activity.splashShowing()));
                if (splash.get()) SystemClock.sleep(50);
            }
            instrumentation.runOnMainSync(() -> {
                assertFalse(activity.findViewById(R.id.splash_version).isShown());
                var navigation = (com.google.android.material.navigation.NavigationBarView)
                        activity.findViewById(R.id.navigation);
                navigation.setSelectedItemId(R.id.nav_settings);
                TextView version = activity.findViewById(R.id.settings_app_version);
                assertTrue(version.isShown());
                assertEquals(displayVersion, version.getText().toString());
                View row = activity.findViewById(R.id.settings_version_row);
                var section = (ViewGroup) row.getParent();
                assertSame(row, section.getChildAt(section.getChildCount() - 1));
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void settingsListOpensSwitchPagesAndReturnsToTheList() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ScannerActivity activity = launch(instrumentation);
        ScanPreferences preferences = new ScanPreferences(activity);
        DetectionSettings detectionSettings = new DetectionSettings(activity);
        boolean originalAdvanced = preferences.advanced();
        boolean originalAutomatic = detectionSettings.automatic();
        try {
            instrumentation.runOnMainSync(() -> {
                var navigation = (com.google.android.material.navigation.NavigationBarView)
                        activity.findViewById(R.id.navigation);
                navigation.setSelectedItemId(R.id.nav_settings);
                assertNull(activity.findViewById(R.id.advanced_scanning));
                assertNull(activity.findViewById(R.id.settings_alert_toggle));
                activity.findViewById(R.id.advanced_scan_settings).performClick();
                MaterialSwitch advanced = activity.findViewById(R.id.advanced_scanning);
                assertEquals(originalAdvanced, advanced.isChecked());
                advanced.setChecked(true);
                assertTrue(preferences.advanced());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.automatic_scanning).getVisibility());
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_screen).getVisibility());
                for (int row : new int[]{R.id.candidate_alert_settings, R.id.overlay_alert_settings}) {
                    activity.findViewById(row).performClick();
                    MaterialSwitch alert = activity.findViewById(R.id.settings_alert_toggle);
                    boolean expected = row == R.id.candidate_alert_settings ? preferences.alerts()
                            : preferences.overlayAlerts() && android.provider.Settings.canDrawOverlays(activity);
                    assertEquals(expected, alert.isChecked());
                    activity.getOnBackPressedDispatcher().onBackPressed();
                    assertNull(activity.findViewById(R.id.settings_alert_toggle));
                }
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            preferences.advanced(originalAdvanced);
            detectionSettings.automatic(originalAutomatic);
        }
    }

    @Test public void backReturnsToScanAndConfirmsStopOrExit() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ScannerActivity activity = launch(instrumentation);
        try {
            instrumentation.runOnMainSync(() -> {
                assertFalse(activity.splashShowing());
                var navigation = (com.google.android.material.navigation.NavigationBarView)
                        activity.findViewById(R.id.navigation);
                for (int tab : new int[]{R.id.nav_history, R.id.nav_settings}) {
                    navigation.setSelectedItemId(tab);
                    activity.getOnBackPressedDispatcher().onBackPressed();
                    assertEquals(R.id.nav_scan, navigation.getSelectedItemId());
                }
                var model = new androidx.lifecycle.ViewModelProvider(activity).get(ScanViewModel.class);
                model.starting = true;
                activity.getOnBackPressedDispatcher().onBackPressed();
            });
            var automation = instrumentation.getUiAutomation();
            automation.waitForIdle(200, 5000);
            assertFalse(automation.getRootInActiveWindow().findAccessibilityNodeInfosByText(
                    activity.getString(R.string.scan_stop_confirmation)).isEmpty());
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                var model = new androidx.lifecycle.ViewModelProvider(activity).get(ScanViewModel.class);
                assertTrue(model.starting);
                model.starting = false;
                activity.getOnBackPressedDispatcher().onBackPressed();
            });
            automation.waitForIdle(200, 5000);
            var window = automation.getRootInActiveWindow();
            assertFalse(window.findAccessibilityNodeInfosByText(activity.getString(R.string.exit_confirmation)).isEmpty());
            var exit = window.findAccessibilityNodeInfosByViewId("android:id/button1");
            assertFalse(exit.isEmpty());
            assertTrue(exit.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> assertTrue(activity.isFinishing()));
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void categorySelectionSavesImmediatelyAndKeepsAtLeastOneType() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        DetectionSettings settings = new DetectionSettings(context);
        Set<DeviceCategory> original = settings.selected();
        Set<DeviceCategory> initial = Set.of(DeviceCategory.GLASSES);
        settings.apply(initial);
        ScannerActivity activity = launch(instrumentation);
        try {
            instrumentation.runOnMainSync(() -> {
                var navigation = (com.google.android.material.navigation.NavigationBarView)
                        activity.findViewById(R.id.navigation);
                navigation.setSelectedItemId(R.id.nav_settings);
                activity.findViewById(R.id.categories_setting).performClick();
                var host = (ViewGroup) activity.findViewById(R.id.settings_page);
                View page = host.getChildAt(0);
                assertNotNull(page);
                assertEquals(View.GONE, activity.findViewById(R.id.navigation).getVisibility());
                assertNull(check(page, DeviceCategory.CUSTOM));
                assertNull(activity.findViewById(R.id.settings_page_save));
                assertNull(activity.findViewById(R.id.settings_page_cancel));
                check(page, DeviceCategory.GLASSES).performClick();
                assertTrue(page.isShown());
                assertTrue(check(page, DeviceCategory.GLASSES).isChecked());
                assertEquals(initial, settings.selected());
            });

            var automation = instrumentation.getUiAutomation();
            automation.waitForIdle(250, 5000);
            var window = automation.getRootInActiveWindow();
            assertNotNull(window);
            assertFalse(window.findAccessibilityNodeInfosByText(
                    context.getString(R.string.detection_categories_required)).isEmpty());
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            instrumentation.waitForIdleSync();

            instrumentation.runOnMainSync(() -> {
                var host = (ViewGroup) activity.findViewById(R.id.settings_page);
                View page = host.getChildAt(0);
                assertTrue(page.isShown());
                check(page, DeviceCategory.CAMERA).performClick();
                assertEquals(Set.of(DeviceCategory.GLASSES, DeviceCategory.CAMERA), settings.selected());
                check(page, DeviceCategory.GLASSES).performClick();
                assertEquals(Set.of(DeviceCategory.CAMERA), settings.selected());
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_screen).getVisibility());

                ((com.google.android.material.navigation.NavigationBarView) activity.findViewById(R.id.navigation))
                        .setSelectedItemId(R.id.nav_scan);
                activity.findViewById(R.id.select_categories).performClick();
                page = host.getChildAt(0);
                assertTrue(check(page, DeviceCategory.CAMERA).isChecked());
                assertFalse(check(page, DeviceCategory.GLASSES).isChecked());
                check(page, DeviceCategory.WATCH).performClick();
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.scan_screen).getVisibility());
                assertEquals(Set.of(DeviceCategory.CAMERA, DeviceCategory.WATCH), new DetectionSettings(context).selected());
                View settingsButton = activity.findViewById(R.id.select_categories);
                assertEquals("", ((TextView) settingsButton).getText().toString());
                assertEquals(context.getString(R.string.detection_settings_button), settingsButton.getContentDescription());
                assertSame(activity.findViewById(R.id.scan_action).getParent(), settingsButton.getParent());
                assertEquals(activity.findViewById(R.id.scan_action).getLayoutParams().height,
                        settingsButton.getLayoutParams().height);

            });
        } finally {
            instrumentation.runOnMainSync(() -> {
                activity.finish();
            });
            settings.apply(original);
        }
    }

    @Test public void candidateFilterSwitchChangesTheListButKeepsTheTotalCount() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        ScanPreferences preferences = new ScanPreferences(context);
        boolean originalAdvanced = preferences.advanced();
        preferences.advanced(false);
        ScannerActivity activity = launch(instrumentation);
        ScanViewModel model = new ScanViewModel();
        AtomicReference<ScanScreen> screen = new AtomicReference<>();
        try {
            instrumentation.runOnMainSync(() -> {
                RadioScanner.Signal fresh = new RadioScanner.Signal(RadioScanner.Mode.BLE, "test-fresh", "Camera_test",
                        -55, Set.of(), Set.of(), Set.of());
                RadioScanner.Signal unknown = new RadioScanner.Signal(RadioScanner.Mode.WIFI, "test-unknown", "Unclassified AP",
                        -61, Set.of(), Set.of(), Set.of());
                RadioScanner.Signal previous = new RadioScanner.Signal(RadioScanner.Mode.WIFI, "test-old", "Previous AP",
                        -65, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), null, null,
                        RadioScanner.NameOrigin.WIFI_SSID, SystemClock.elapsedRealtime() - 20_000,
                        null, 2412, null, true);
                var match = new DeviceMatcher.Match("테스트 등록", "이름 일치", 1, List.of(), DeviceCategory.CAMERA,
                        DetectionRule.EvidenceLevel.USER, "presentation-rule", "", false, true);
                SessionConfig camera = SessionConfig.create(Set.of(DeviceCategory.CAMERA),
                        new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of()), RadioScanner.Mode.BLE, true);
                model.scan = new ScanService.Snapshot("presentation-test", ScanService.State.RUNNING,
                        RadioScanner.Mode.ALL, RadioScanner.Mode.BLE, "", SystemClock.elapsedRealtime(), 0, false,
                        List.of(new ScanObservation(fresh, List.of(match)), new ScanObservation(unknown, List.of()),
                                new ScanObservation(previous, List.of(match))), 1, false,
                        camera, Map.of(
                        RadioScanner.Mode.BLE, new RadioScanner.PathStatus(RadioScanner.PathState.COMPLETE, "완료", 0),
                        RadioScanner.Mode.CLASSIC, new RadioScanner.PathStatus(RadioScanner.PathState.NOT_RUN, "선택한 경로에 포함되지 않음", 0),
                        RadioScanner.Mode.WIFI, new RadioScanner.PathStatus(RadioScanner.PathState.STALE, "이전 결과", 0)));
                screen.set(new ScanScreen(activity, model, preferences));
                screen.get().render();
                assertEquals(View.GONE, activity.findViewById(R.id.scan_status).getVisibility());
                MaterialSwitch filter = activity.findViewById(R.id.only_candidates);
                assertEquals(View.VISIBLE, filter.getVisibility());
                assertEquals("의심 기기만 보기", filter.getText().toString());
                assertTrue(filter.isChecked());
                assertEquals("스캔 기기 1종 · 전체 스캔 3건 · 의심 기기 1건",
                        ((TextView) activity.findViewById(R.id.counts)).getText().toString());
            });
            awaitCount(instrumentation, activity, 1);
            instrumentation.runOnMainSync(() -> ((MaterialSwitch) activity.findViewById(R.id.only_candidates)).setChecked(false));
            awaitCount(instrumentation, activity, 3);
            instrumentation.runOnMainSync(() -> {
                assertEquals("스캔 기기 1종 · 전체 스캔 3건 · 의심 기기 1건",
                        ((TextView) activity.findViewById(R.id.counts)).getText().toString());
                ((MaterialSwitch) activity.findViewById(R.id.only_candidates)).setChecked(true);
            });
            awaitCount(instrumentation, activity, 1);
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (screen.get() != null) screen.get().close();
                activity.finish();
            });
            preferences.advanced(originalAdvanced);
        }
    }

    @Test public void scanButtonShowsRunningStateWithoutListingSearchRoutes() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ScannerActivity activity = launch(instrumentation);
        AtomicReference<ScanScreen> screen = new AtomicReference<>();
        try {
            instrumentation.runOnMainSync(() -> {
                ScanViewModel model = new ScanViewModel();
                SessionConfig glasses = SessionConfig.create(Set.of(DeviceCategory.GLASSES),
                        new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of()), RadioScanner.Mode.BLE, true);
                model.scan = new ScanService.Snapshot("routes-test", ScanService.State.RUNNING,
                        RadioScanner.Mode.BLE, RadioScanner.Mode.BLE, "BLE 검색 중",
                        SystemClock.elapsedRealtime() - 65_000, 180_000, false,
                        List.of(), 0, false, glasses, Map.of(
                        RadioScanner.Mode.BLE, new RadioScanner.PathStatus(RadioScanner.PathState.COMPLETE, "완료", 0),
                        RadioScanner.Mode.CLASSIC, new RadioScanner.PathStatus(RadioScanner.PathState.NOT_RUN, "선택한 경로에 포함되지 않음", 0),
                        RadioScanner.Mode.WIFI, new RadioScanner.PathStatus(RadioScanner.PathState.FAILED, "실행하지 않은 경로", 0)));
                screen.set(new ScanScreen(activity, model, new ScanPreferences(activity)));
                screen.get().render();
                TextView action = activity.findViewById(R.id.scan_action);
                assertTrue(action.getText().toString().contains("스캔 중지\n스캔 중"));
                assertTrue(action.getText().toString().endsWith("1:05"));
                assertEquals(View.GONE, activity.findViewById(R.id.scan_status).getVisibility());
                assertEquals("선택한 유형의 새 신호를 기다리는 중입니다.",
                        ((TextView) activity.findViewById(R.id.empty)).getText().toString());
                model.scan = new ScanService.Snapshot("routes-test", ScanService.State.STOPPED,
                        RadioScanner.Mode.BLE, RadioScanner.Mode.BLE, "", 0, 0, false,
                        List.of(), 0, false);
                screen.get().render();
                assertEquals(activity.getString(R.string.scan_start), action.getText().toString());
                assertEquals(View.GONE, activity.findViewById(R.id.scan_status).getVisibility());
            });
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (screen.get() != null) screen.get().close();
                activity.finish();
            });
        }
    }

    private ScannerActivity launch(Instrumentation instrumentation) {
        var context = instrumentation.getTargetContext();
        ScannerActivity activity = (ScannerActivity) instrumentation.startActivitySync(
                new Intent(context, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        java.util.concurrent.atomic.AtomicBoolean splash = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            instrumentation.runOnMainSync(() -> splash.set(activity.splashShowing()));
            if (!splash.get()) return activity;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertFalse("스플래시 화면이 닫혀야 합니다.", splash.get());
        return activity;
    }

    private MaterialSwitch check(View page, DeviceCategory category) {
        return findCheck(page, category.label);
    }

    private MaterialSwitch findCheck(View view, String label) {
        if (view instanceof MaterialSwitch toggle && label.contentEquals(toggle.getText())) return toggle;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                MaterialSwitch result = findCheck(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }

    private void awaitCount(Instrumentation instrumentation, ScannerActivity activity, int expected) {
        AtomicInteger count = new AtomicInteger(-1);
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> count.set(((RecyclerView) activity.findViewById(R.id.results))
                    .getAdapter().getItemCount()));
            if (count.get() == expected) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertEquals(expected, count.get());
    }
}
