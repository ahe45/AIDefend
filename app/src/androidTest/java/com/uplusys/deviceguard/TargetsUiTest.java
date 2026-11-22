package com.uplusys.deviceguard;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class TargetsUiTest {
    @Test public void deletesBuiltinAfterConfirmationAndPersistsSelection() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        var automation = instrumentation.getUiAutomation();
        var context = instrumentation.getTargetContext();
        assumeTrue(PermissionSetup.ready(context));
        SavedTargets store = new SavedTargets(context);
        assumeTrue(!store.builtins().isEmpty());
        DeviceCatalog.Entry entry = store.builtins().get(0);
        var preferences = context.getSharedPreferences("saved_targets", android.content.Context.MODE_PRIVATE);
        java.util.Set<String> original = preferences.getStringSet("deleted_builtin_targets", null);
        if (original != null) original = new java.util.HashSet<>(original);
        ScannerActivity activity = (ScannerActivity) instrumentation.startActivitySync(
                new Intent(context, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<View> opened = new AtomicReference<>();
        try {
            awaitSplash(instrumentation, activity);
            instrumentation.runOnMainSync(() -> opened.set(new TargetsEditor(activity).show()));
            search(instrumentation, activity, entry.label());
            View browser = opened.get();
            awaitCount(instrumentation, browser, 1);
            for (boolean confirm : new boolean[]{false, true}) {
                instrumentation.runOnMainSync(() -> {
                    RecyclerView list = browser.findViewById(R.id.targets_list);
                    list.findViewHolderForAdapterPosition(0).itemView.performClick();
                });
                instrumentation.waitForIdleSync();
                automation.waitForIdle(300, 5000);
                var root = automation.getRootInActiveWindow();
                assertNotNull(root);
                assertTrue(root.findAccessibilityNodeInfosByText("삭제").get(0)
                        .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
                instrumentation.waitForIdleSync();
                automation.waitForIdle(300, 5000);
                root = automation.getRootInActiveWindow();
                assertNotNull(root);
                assertFalse(root.findAccessibilityNodeInfosByText("이 탐색 대상을 삭제할까요?").isEmpty());
                assertTrue(root.findAccessibilityNodeInfosByText(confirm ? "삭제" : "취소").stream()
                        .filter(node -> "android.widget.Button".contentEquals(node.getClassName()))
                        .anyMatch(node -> node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)));
                if (!confirm) instrumentation.runOnMainSync(activity::closeSettingsPage);
                awaitCount(instrumentation, browser, confirm ? 0 : 1);
                assertEquals(confirm, new SavedTargets(context).snapshot().deletedBuiltins().contains(entry.label()));
            }
            assertFalse(new SavedTargets(context).builtins().contains(entry));
        } finally {
            instrumentation.runOnMainSync(() -> {
                activity.finish();
            });
            var editor = preferences.edit();
            if (original == null) editor.remove("deleted_builtin_targets");
            else editor.putStringSet("deleted_builtin_targets", original);
            editor.commit();
        }
    }

    @Test public void searchesCardsFiltersCustomTargetsAndRetainsQueryAfterEditing() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        assumeTrue(PermissionSetup.ready(context));
        SavedTargets store = new SavedTargets(context);
        String name = "TargetUiTest_" + UUID.randomUUID();
        int baseline = store.builtins().size() + store.rules().size();
        store.change(false, name, true);
        ScannerActivity activity = (ScannerActivity) instrumentation.startActivitySync(
                new Intent(context, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<View> opened = new AtomicReference<>();
        try {
            awaitSplash(instrumentation, activity);
            instrumentation.runOnMainSync(() -> {
                var navigation = (com.google.android.material.navigation.NavigationBarView)
                        activity.findViewById(R.id.navigation);
                navigation.setSelectedItemId(R.id.nav_settings);
                var manage = (android.widget.TextView) activity.findViewById(R.id.manage_targets);
                assertEquals("의심 기기 대상 관리", manage.getText().toString());
                manage.performClick();
                var host = (android.view.ViewGroup) activity.findViewById(R.id.settings_page);
                opened.set(host.getChildAt(0));
            });
            View dialog = opened.get();
            awaitCount(instrumentation, dialog, baseline + 1);
            instrumentation.runOnMainSync(() -> {
                assertNull(dialog.findViewById(R.id.targets_search));
                assertNull(dialog.findViewById(R.id.targets_category));
                assertNull(dialog.findViewById(R.id.targets_filters));
            });
            selectFilters(instrumentation, activity, "스마트워치", 0);
            int watches = (int) (store.builtins().stream().filter(entry -> entry.category() == DeviceCategory.WATCH).count()
                    + store.rules().stream().filter(rule -> rule.category() == DeviceCategory.WATCH).count());
            awaitCount(instrumentation, dialog, watches);
            search(instrumentation, activity, name);
            awaitCount(instrumentation, dialog, 0);
            selectFilters(instrumentation, activity, "전체", 0);
            awaitCount(instrumentation, dialog, 1);
            search(instrumentation, activity, "eVeN G2");
            awaitCount(instrumentation, dialog, 1);
            search(instrumentation, activity, DeviceCatalog.G2_UUID);
            awaitCount(instrumentation, dialog, 1);
            search(instrumentation, activity, "unknown_" + name);
            awaitCount(instrumentation, dialog, 0);
            instrumentation.runOnMainSync(() -> assertEquals(View.VISIBLE,
                    dialog.findViewById(R.id.targets_empty).getVisibility()));
            search(instrumentation, activity, name);
            selectFilters(instrumentation, activity, null, R.id.targets_custom);
            awaitCount(instrumentation, dialog, 1);
            instrumentation.runOnMainSync(() -> {
                RecyclerView list = dialog.findViewById(R.id.targets_list);
                RecyclerView.ViewHolder row = list.findViewHolderForAdapterPosition(0);
                assertNotNull(row);
                row.itemView.performClick();
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertTrue(activity.findViewById(R.id.target_device_category).isShown());
                assertNull(activity.findViewById(R.id.targets_search_action));
                assertNull(activity.findViewById(R.id.targets_filter_action));
                activity.getOnBackPressedDispatcher().onBackPressed();
            });
            openPopup(instrumentation, activity, R.id.targets_search_action);
            assertEquals(name, popupView(instrumentation, R.id.targets_search).getText().toString());
            editSearch(instrumentation, "");
            openPopup(instrumentation, activity, R.id.targets_filter_action);
            assertTrue(popupView(instrumentation, R.id.targets_custom).isChecked());
            assertTrue(popupView(instrumentation, R.id.targets_builtin).performAction(AccessibilityNodeInfo.ACTION_CLICK));
            closePopup(instrumentation);
            awaitCount(instrumentation, dialog, store.builtins().size());
        } finally {
            instrumentation.runOnMainSync(() -> {
                activity.finish();
            });
            store.change(false, name, false);
        }
    }

    private void search(Instrumentation instrumentation, ScannerActivity activity, String query) throws Exception {
        openPopup(instrumentation, activity, R.id.targets_search_action);
        editSearch(instrumentation, query);
    }

    private void editSearch(Instrumentation instrumentation, String query) throws Exception {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query);
        assertTrue(popupView(instrumentation, R.id.targets_search).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
        closePopup(instrumentation);
    }

    private void selectFilters(Instrumentation instrumentation, ScannerActivity activity, String category, int registration)
            throws Exception {
        openPopup(instrumentation, activity, R.id.targets_filter_action);
        if (category != null) {
            var categories = popupView(instrumentation, R.id.targets_category);
            assertEquals(5, categories.getChildCount());
            assertTrue(categories.findAccessibilityNodeInfosByText(category).get(0)
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK));
        }
        if (registration != 0) {
            assertTrue(popupView(instrumentation, registration).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        }
        closePopup(instrumentation);
    }

    private void openPopup(Instrumentation instrumentation, ScannerActivity activity, int id) {
        instrumentation.runOnMainSync(() -> {
            View action = activity.findViewById(id);
            assertNotNull(action);
            assertTrue(action.performClick());
        });
    }

    private AccessibilityNodeInfo popupView(Instrumentation instrumentation, int id) throws Exception {
        var automation = instrumentation.getUiAutomation();
        automation.waitForIdle(200, 5000);
        var root = automation.getRootInActiveWindow();
        assertNotNull(root);
        String name = instrumentation.getTargetContext().getResources().getResourceName(id);
        var views = root.findAccessibilityNodeInfosByViewId(name);
        assertFalse(name, views.isEmpty());
        return views.get(0);
    }

    private void closePopup(Instrumentation instrumentation) throws Exception {
        assertTrue(popupView(instrumentation, android.R.id.button1).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        instrumentation.waitForIdleSync();
    }

    private void awaitSplash(Instrumentation instrumentation, ScannerActivity activity) {
        var visible = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            instrumentation.runOnMainSync(() -> visible.set(activity.splashShowing()));
            if (!visible.get()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertFalse(visible.get());
    }

    private void awaitCount(Instrumentation instrumentation, View dialog, int expected) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        AtomicInteger count = new AtomicInteger(-1);
        do {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                RecyclerView list = dialog.findViewById(R.id.targets_list);
                count.set(list.getAdapter().getItemCount());
            });
            if (count.get() == expected) return;
            SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertEquals(expected, count.get());
    }
}
