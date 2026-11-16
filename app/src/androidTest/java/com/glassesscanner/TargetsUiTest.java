package com.glassesscanner;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.KeyEvent;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
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
        AtomicReference<AlertDialog> opened = new AtomicReference<>();
        try {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                opened.set(new TargetsEditor(activity).show());
                search(opened.get()).setText(entry.label());
            });
            AlertDialog browser = opened.get();
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
                awaitCount(instrumentation, browser, confirm ? 0 : 1);
                assertEquals(confirm, new SavedTargets(context).snapshot().deletedBuiltins().contains(entry.label()));
            }
            assertFalse(new SavedTargets(context).builtins().contains(entry));
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (opened.get() != null) opened.get().dismiss();
                activity.finish();
            });
            var editor = preferences.edit();
            if (original == null) editor.remove("deleted_builtin_targets");
            else editor.putStringSet("deleted_builtin_targets", original);
            editor.commit();
        }
    }

    @Test public void searchesCardsFiltersCustomTargetsAndRetainsQueryAfterEditing() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        assumeTrue(PermissionSetup.ready(context));
        SavedTargets store = new SavedTargets(context);
        String name = "TargetUiTest_" + UUID.randomUUID();
        int baseline = store.builtins().size() + store.values(false).size()
                + store.values(true).size() + store.manufacturerIds().size();
        store.change(false, name, true);
        ScannerActivity activity = (ScannerActivity) instrumentation.startActivitySync(
                new Intent(context, ScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<AlertDialog> opened = new AtomicReference<>();
        try {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> opened.set(new TargetsEditor(activity).show()));
            AlertDialog dialog = opened.get();
            awaitCount(instrumentation, dialog, baseline + 1);
            instrumentation.runOnMainSync(() -> search(dialog).setText("eVeN G2"));
            awaitCount(instrumentation, dialog, 1);
            instrumentation.runOnMainSync(() -> search(dialog).setText(DeviceCatalog.G2_UUID));
            awaitCount(instrumentation, dialog, 1);
            instrumentation.runOnMainSync(() -> search(dialog).setText("unknown_" + name));
            awaitCount(instrumentation, dialog, 0);
            instrumentation.runOnMainSync(() -> assertEquals(View.VISIBLE,
                    dialog.findViewById(R.id.targets_empty).getVisibility()));
            instrumentation.runOnMainSync(() -> {
                search(dialog).setText(name);
                ((ChipGroup) dialog.findViewById(R.id.targets_filters)).check(R.id.targets_custom);
            });
            awaitCount(instrumentation, dialog, 1);
            instrumentation.runOnMainSync(() -> {
                RecyclerView list = dialog.findViewById(R.id.targets_list);
                RecyclerView.ViewHolder row = list.findViewHolderForAdapterPosition(0);
                assertNotNull(row);
                row.itemView.performClick();
            });
            instrumentation.waitForIdleSync();
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertTrue(dialog.isShowing());
                assertEquals(name, search(dialog).getText().toString());
                assertEquals(R.id.targets_custom, ((ChipGroup) dialog.findViewById(R.id.targets_filters)).getCheckedChipId());
                search(dialog).setText("");
                ((ChipGroup) dialog.findViewById(R.id.targets_filters)).check(R.id.targets_builtin);
            });
            awaitCount(instrumentation, dialog, store.builtins().size());
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (opened.get() != null) opened.get().dismiss();
                activity.finish();
            });
            store.change(false, name, false);
        }
    }

    private TextInputEditText search(AlertDialog dialog) { return dialog.findViewById(R.id.targets_search); }

    private void awaitCount(Instrumentation instrumentation, AlertDialog dialog, int expected) {
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
