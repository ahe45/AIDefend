package com.uplusys.deviceguard;

import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.button.MaterialButton;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ScannerUiTest {
    @Test public void permissionCardsFitTabletWithEverySetupActionVisible() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        var configuration = context.getResources().getConfiguration();
        org.junit.Assume.assumeTrue(configuration.screenWidthDp >= 600 && configuration.screenHeightDp >= 880
                && configuration.fontScale <= 1.1f);
        ScannerActivity activity = (ScannerActivity) instrumentation.startActivitySync(
                new Intent(context, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                activity.findViewById(R.id.permission_screen).setVisibility(View.VISIBLE);
                activity.findViewById(R.id.app_content).setVisibility(View.GONE);
                activity.findViewById(R.id.toolbar).setVisibility(View.GONE);
                activity.findViewById(R.id.navigation).setVisibility(View.GONE);
                for (int id : new int[]{R.id.permission_location_card, R.id.permission_nearby_card,
                        R.id.permission_notifications_card, R.id.permission_overlay_card,
                        R.id.permission_background_card}) {
                    activity.findViewById(id).findViewById(R.id.permission_card_action).setVisibility(View.VISIBLE);
                }
                View root = activity.findViewById(R.id.root);
                root.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
                root.layout(root.getLeft(), root.getTop(), root.getRight(), root.getBottom());
                android.widget.ScrollView scroll = activity.findViewById(R.id.permission_screen);
                View content = activity.findViewById(R.id.permission_content);
                content.measure(View.MeasureSpec.makeMeasureSpec(content.getWidth(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                assertTrue("All setup cards and footer must fit in the tablet viewport",
                        content.getMeasuredHeight() <= scroll.getHeight());
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void scanStartShowsMissingRadioSettingsAndRetryRechecks() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var automation = instrumentation.getUiAutomation();
        var context = instrumentation.getTargetContext();
        SessionConfig configured = new DetectionSettings(context).snapshot(new SavedTargets(context).snapshot(),
                new ScanPreferences(context).mode());
        org.junit.Assume.assumeFalse(ScanPolicy.startupProblems(context, configured).isEmpty());
        org.junit.Assume.assumeTrue(PermissionSetup.ready(context));
        ScannerActivity activity = null;
        try {
            activity = (ScannerActivity) instrumentation.startActivitySync(
                    new Intent(context, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ScannerActivity scanner = activity;
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(scanner::scanAction);
            instrumentation.waitForIdleSync();
            automation.waitForIdle(500, 5000);
            var root = automation.getRootInActiveWindow();
            assertNotNull(root);
            assertFalse(root.findAccessibilityNodeInfosByText(context.getString(R.string.scan_setup_title)).isEmpty());
            var retry = root.findAccessibilityNodeInfosByText(context.getString(R.string.scan_setup_retry));
            assertFalse(retry.isEmpty());
            assertTrue(retry.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            instrumentation.waitForIdleSync();
            automation.waitForIdle(500, 5000);
            root = automation.getRootInActiveWindow();
            assertNotNull(root);
            assertFalse(root.findAccessibilityNodeInfosByText(context.getString(R.string.scan_setup_title)).isEmpty());
            instrumentation.runOnMainSync(() -> assertFalse(scanner.scanRunning()));
        } finally {
            if (activity != null) {
                ScannerActivity scanner = activity;
                instrumentation.runOnMainSync(scanner::finish);
            }
        }
    }

    @Test public void radioRequirementsHaveModalSettingsActions() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        ScannerActivity activity = (ScannerActivity) instrumentation.startActivitySync(
                new Intent(context, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                var dialog = activity.showScanSetup(List.of(
                        new ScanPolicy.Problem("Bluetooth를 켜 주세요.", Settings.ACTION_BLUETOOTH_SETTINGS),
                        new ScanPolicy.Problem("Wi-Fi를 켜 주세요.", Settings.ACTION_WIFI_SETTINGS),
                        new ScanPolicy.Problem("위치 서비스를 켜 주세요.", Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
                assertTrue(dialog.isShowing());
                for (int title : new int[]{R.string.scan_setup_bluetooth, R.string.scan_setup_wifi, R.string.scan_setup_location}) {
                    java.util.ArrayList<View> matches = new java.util.ArrayList<>();
                    dialog.getWindow().getDecorView().findViewsWithText(matches, activity.getString(title), View.FIND_VIEWS_WITH_TEXT);
                    assertTrue(matches.stream().anyMatch(view -> view instanceof MaterialButton
                            && ((MaterialButton) view).getIcon() != null));
                }
                assertEquals(activity.getString(R.string.scan_setup_retry),
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).getText().toString());
                assertFalse(activity.scanRunning());
                dialog.dismiss();
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void permissionGateAndScanStatePresentation() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        ScannerActivity activity = (ScannerActivity) instrumentation.startActivitySync(
                new Intent(context, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                boolean ready = PermissionSetup.ready(activity);
                assertEquals(ready ? View.GONE : View.VISIBLE, activity.findViewById(R.id.permission_screen).getVisibility());
                assertEquals(ready ? View.VISIBLE : View.GONE, activity.findViewById(R.id.app_content).getVisibility());
                assertEquals(ready ? View.VISIBLE : View.GONE, activity.findViewById(R.id.navigation).getVisibility());
                assertEquals(ready ? View.VISIBLE : View.GONE, activity.findViewById(R.id.toolbar).getVisibility());
                int[] cards = {R.id.permission_location_card, R.id.permission_nearby_card,
                        R.id.permission_notifications_card, R.id.permission_overlay_card, R.id.permission_background_card};
                boolean[] allowed = {
                        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                        Build.VERSION.SDK_INT < 31 || (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                                == PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                                == PackageManager.PERMISSION_GRANTED),
                        PermissionSetup.notificationsReady(context), Settings.canDrawOverlays(context), BackgroundSettings.batteryExempt(context)
                };
                int total = 5;
                int completed = 0;
                for (int i = 0; i < total; i++) {
                    View card = activity.findViewById(cards[i]);
                    TextView badge = card.findViewById(R.id.permission_badge);
                    int status = allowed[i] ? R.string.permission_complete : R.string.permission_needed;
                    assertEquals(activity.getString(status),
                            badge.getText().toString());
                    assertNotNull(badge.getCompoundDrawablesRelative()[0]);
                    assertNotNull(((ImageView) card.findViewById(R.id.permission_icon)).getDrawable());
                    MaterialButton settings = card.findViewById(R.id.permission_card_action);
                    assertNotNull(settings.getIcon());
                    assertEquals(allowed[i] ? View.GONE : View.VISIBLE, settings.getVisibility());
                    if (allowed[i]) completed++;
                }
                assertEquals(completed == total, ready);
                assertEquals(activity.getString(R.string.permission_progress, completed, total),
                        ((TextView) activity.findViewById(R.id.permission_state)).getText().toString());
                assertNotNull(activity.findViewById(R.id.background_battery));
                assertNotNull(((MaterialButton) activity.findViewById(R.id.permission_continue)).getIcon());
                if (!ready) {
                    activity.scanAction();
                    assertEquals(View.VISIBLE, activity.findViewById(R.id.permission_screen).getVisibility());
                    assertFalse(activity.scanRunning());
                }
                // Verify presentation without starting the radio or changing device permissions.
                ScanViewModel model = new ScanViewModel();
                ScanScreen screen = new ScanScreen(activity, model, new ScanPreferences(activity));
                screen.render();
                MaterialButton action = activity.findViewById(R.id.scan_action);
                assertEquals("스캔 시작", action.getText().toString());
                assertEquals(0xFF17653A, action.getBackgroundTintList().getDefaultColor());
                assertNotNull(action.getIcon());
                assertEquals(activity.getResources().getDimensionPixelSize(R.dimen.scan_control_height),
                        action.getLayoutParams().height);
                model.scan = new ScanService.Snapshot("ui-test", ScanService.State.RUNNING,
                        RadioScanner.Mode.BLE, RadioScanner.Mode.BLE, "", 0, 0, false, List.of(), 0, false);
                screen.render();
                assertTrue(action.getText().toString().startsWith("스캔 중지\n스캔 중"));
                assertEquals(0xFFB3261E, action.getBackgroundTintList().getDefaultColor());
                assertEquals(Color.WHITE, action.getCurrentTextColor());
                assertNotNull(action.getIcon());
                screen.close();
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
