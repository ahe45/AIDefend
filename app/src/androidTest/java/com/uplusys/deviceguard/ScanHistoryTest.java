package com.uplusys.deviceguard;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ScanHistoryTest {
    @Test public void importsOnceAndRecoversUnfinishedSession() throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String storageName = "scan_history_test_" + UUID.randomUUID();
        try {
            assertTrue(testContext.getSharedPreferences(storageName, Context.MODE_PRIVATE).edit()
                    .putString("sessions", "[{\"title\":\"old title\",\"body\":\"old body\"}]").commit());
            verifyImportAndRecovery(testContext, storageName);
        } finally {
            testContext.deleteDatabase(storageName + ".db");
            testContext.deleteSharedPreferences(storageName);
        }
    }

    private void verifyImportAndRecovery(Context testContext, String storageName) throws Exception {
        long start = System.currentTimeMillis() - 1_000;
        try (ScanHistory first = new ScanHistory(testContext, storageName)) {
            ScanHistory.Detail legacy = await(callback -> first.detail("legacy-0", callback));
            assertNotNull(legacy);
            assertEquals("old body", legacy.observations());

            first.begin("finished", start, "BLE");
            RadioScanner.Signal signal = new RadioScanner.Signal(RadioScanner.Mode.BLE, "01:23:45:67:89:AB",
                    "Vuzix Z100", -60, Collections.singleton(0x060C), Collections.emptySet(),
                    Collections.singleton(DeviceCatalog.G2_UUID), Collections.singletonMap(0x060C, "0123ABCD"),
                    Collections.singletonMap(DeviceCatalog.G2_UUID, "FF10"));
            ScanObservation observation = new ScanObservation(signal, Collections.emptyList());
            assertEquals(Boolean.TRUE, this.<Boolean>await(callback -> first.save("finished", start + 1, "completed",
                    "스캔 완료", 1, 0, 0, Collections.singletonList(observation), callback)));
            ScanHistory.Detail saved = await(callback -> first.detail("finished", callback));
            assertFalse(saved.observations().contains("0123ABCD"));
            assertFalse(saved.observations().contains("FF10"));
            assertFalse(saved.observations().contains("01:23:45:67:89:AB"));
            assertFalse(saved.observations().contains("Vuzix Z100"));
            assertTrue(saved.observations().contains("BLE"));
            assertTrue(saved.observations().contains("-60 dBm"));
            assertEquals(Boolean.FALSE, this.<Boolean>await(callback -> first.save("finished", start + 2, "stopped",
                    "스캔 중지", 0, 0, 0, Collections.emptyList(), callback)));

            first.begin("interrupted", start + 3, "BLE");
            assertEquals(Boolean.TRUE, this.<Boolean>await(callback -> first.save("interrupted", 0, "running",
                    "스캔 중", 0, 0, 0, Collections.emptyList(), callback)));
        }
        try (ScanHistory reopened = new ScanHistory(testContext, storageName)) {
            List<ScanHistory.Entry> entries = await(reopened::list);
            assertNotNull(entries);
            assertEquals(3, entries.size());
            assertEquals("interrupted", entries.get(0).id());
            assertTrue(entries.get(0).summary().contains("중단"));
            assertTrue(entries.get(1).summary().contains("완료"));
            assertEquals(Boolean.TRUE, this.<Boolean>await(callback -> reopened.delete("legacy-0", callback)));
            List<ScanHistory.Entry> afterDelete = await(reopened::list);
            assertEquals(2, afterDelete.size());
        }
    }

    @Test public void upgradeKeepsVersionOneHistoryAndStoresFrozenSessionMetadata() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String storage = "history_v1_" + UUID.randomUUID();
        long start = System.currentTimeMillis() - 1_000;
        try {
            try (SQLiteDatabase db = context.openOrCreateDatabase(storage + ".db", Context.MODE_PRIVATE, null)) {
                db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, started INTEGER NOT NULL, ended INTEGER, "
                        + "mode TEXT NOT NULL, state TEXT NOT NULL, reason TEXT NOT NULL, total INTEGER NOT NULL DEFAULT 0, "
                        + "candidates INTEGER NOT NULL DEFAULT 0, omitted INTEGER NOT NULL DEFAULT 0, title TEXT, legacy_body TEXT)");
                db.execSQL("CREATE TABLE observations (session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, "
                        + "observation_key TEXT NOT NULL, position INTEGER NOT NULL, name TEXT NOT NULL, metadata TEXT NOT NULL, "
                        + "reason TEXT NOT NULL, PRIMARY KEY(session_id, observation_key))");
                db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY)");
                db.execSQL("INSERT INTO metadata VALUES ('legacy_imported')");
                db.execSQL("INSERT INTO sessions(id,started,ended,mode,state,reason) VALUES ('v1',?,?,'BLE','completed','이전 기록')",
                        new Object[]{start, start + 1});
                db.execSQL("INSERT INTO observations VALUES ('v1','BLE/old',0,'기존 등록 기기','BLE · -70 dBm','이전 탐지 근거')");
                db.setVersion(1);
            }
            try (ScanHistory history = new ScanHistory(context, storage)) {
                ScanHistory.Detail old = await(callback -> history.detail("v1", callback));
                assertNotNull(old);
                assertTrue(old.observations().contains("기존 등록 기기"));
                assertTrue(old.observations().contains("이전 스캔 근거"));
                assertFalse(old.results().get(0).classificationKnown());
                assertFalse(old.entry().fullResults());
                SessionConfig config = SessionConfig.create(java.util.Set.of(DeviceCategory.CAMERA),
                        new SavedTargets.Snapshot(java.util.Set.of(), java.util.Set.of(), java.util.Set.of()),
                        RadioScanner.Mode.BLE, true);
                history.begin("new", start + 10, config.mode().label, config);
                java.util.Map<RadioScanner.Mode, RadioScanner.PathStatus> paths = java.util.Map.of(
                        RadioScanner.Mode.WIFI, new RadioScanner.PathStatus(RadioScanner.PathState.THROTTLED, "제한", 20),
                        RadioScanner.Mode.BLE, new RadioScanner.PathStatus(RadioScanner.PathState.COMPLETE, "완료", 20));
                assertEquals(Boolean.TRUE, this.<Boolean>await(callback -> history.save("new", start + 30, "completed",
                        "부분 완료", 0, 0, 0, List.of(), config, paths, java.util.Set.of(RadioScanner.Mode.BLE), callback)));
                ScanHistory.Detail saved = await(callback -> history.detail("new", callback));
                assertTrue(saved.entry().summary().contains(DeviceCategory.CAMERA.label));
                assertTrue(saved.entry().summary().contains("검색 요청 제한"));
                assertTrue(saved.entry().summary().contains("완료한 경로"));
                assertTrue(saved.entry().summary().contains(SessionConfig.RULE_VERSION));
            }
        } finally {
            context.deleteDatabase(storage + ".db");
            context.deleteSharedPreferences(storage);
        }
    }

    @Test public void retainsHistoryByDaysAndSupportsUnlimitedRetention() throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String storage = "history_retention_test_" + UUID.randomUUID();
        Context context = new android.content.ContextWrapper(base) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("ScannerActivity".equals(name) ? storage + "_settings" : name, mode);
            }
        };
        try {
            context.getSharedPreferences("ScannerActivity", Context.MODE_PRIVATE).edit().putInt("retention", 10).commit();
            ScanPreferences preferences = new ScanPreferences(context);
            assertEquals(30, preferences.retentionDays());
            long now = System.currentTimeMillis();
            long older = now - TimeUnit.DAYS.toMillis(31);
            try (ScanHistory history = new ScanHistory(context, storage)) {
                saveSession(history, "expired", older, older + 1_000);
                saveSession(history, "recent", older, now - 1_000);
                assertNull(this.<ScanHistory.Detail>await(callback -> history.detail("expired", callback)));
                assertNotNull(this.<ScanHistory.Detail>await(callback -> history.detail("recent", callback)));
                assertEquals(Boolean.TRUE, this.<Boolean>await(callback ->
                        history.changeRetentionDays(ScanPreferences.UNLIMITED_RETENTION, callback)));
                saveSession(history, "unlimited", older, older + 1_000);
                List<ScanHistory.Entry> entries = await(history::list);
                assertEquals(2, entries.size());
                assertEquals(ScanPreferences.UNLIMITED_RETENTION, new ScanPreferences(context).retentionDays());
                assertEquals(Boolean.TRUE, this.<Boolean>await(callback -> history.changeRetentionDays(1, callback)));
                entries = await(history::list);
                assertEquals(List.of("recent"), entries.stream().map(ScanHistory.Entry::id).toList());
            }
        } finally {
            context.deleteDatabase(storage + ".db");
            context.deleteSharedPreferences(storage);
            context.deleteSharedPreferences(storage + "_settings");
        }
    }

    private void saveSession(ScanHistory history, String id, long started, long ended) throws Exception {
        history.begin(id, started, "BLE");
        assertEquals(Boolean.TRUE, this.<Boolean>await(callback ->
                history.save(id, ended, "completed", "스캔 완료", 0, 0, 0, List.of(), callback)));
    }

    @Test public void savedSessionPageFiltersRecordedDevicesWithoutChangingTotals() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        String database = "history_filter_test_" + UUID.randomUUID();
        ScannerActivity activity = (ScannerActivity) instrumentation.startActivitySync(new android.content.Intent(
                context, ScannerActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try (ScanHistory history = new ScanHistory(context, database)) {
            long start = System.currentTimeMillis() - 2_000;
            String id = "history-filter";
            var match = new DeviceMatcher.Match("스마트글래스 의심 기기", "이름 일치", 1);
            var known = new ScanObservation(new RadioScanner.Signal(RadioScanner.Mode.BLE, "sensitive-address",
                    "sensitive-device-name", -52, java.util.Set.of(), java.util.Set.of(), java.util.Set.of()), List.of(match));
            var unknown = new ScanObservation(new RadioScanner.Signal(RadioScanner.Mode.WIFI, "sensitive-bssid",
                    "sensitive-ssid", -65, java.util.Set.of(), java.util.Set.of(), java.util.Set.of()), List.of());
            history.begin(id, start, "통합 스캔");
            assertEquals(Boolean.TRUE, this.<Boolean>await(callback -> history.save(id, start + 1_000, "completed",
                    "스캔 완료", 2, 1, 0, List.of(known, unknown), callback)));
            ScanHistory.Detail stored = await(callback -> history.detail(id, callback));
            assertEquals(start, stored.entry().started());
            assertEquals(start + 1_000, stored.entry().ended());
            assertEquals(2, stored.results().size());
            assertTrue(stored.results().get(0).candidate());
            assertFalse(stored.results().get(1).candidate());
            assertFalse(stored.observations().contains("sensitive-"));

            awaitStartup(instrumentation, activity);
            instrumentation.runOnMainSync(() -> new HistoryScreen(activity, history).open(id));
            awaitCardCount(instrumentation, activity, 1);
            instrumentation.runOnMainSync(() -> {
                var filter = (android.widget.CompoundButton) activity.findViewById(R.id.history_candidates_only);
                assertTrue(filter.isChecked());
                filter.setChecked(false);
            });
            awaitCardCount(instrumentation, activity, 2);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                var counts = (android.widget.TextView) activity.findViewById(R.id.history_session_counts);
                assertEquals("전체 2건 · 의심 기기 1건", counts.getText().toString());
                var list = (androidx.recyclerview.widget.RecyclerView) activity.findViewById(R.id.history_session_results);
                var row = list.findViewHolderForAdapterPosition(0);
                assertNotNull(row);
                assertTrue(row.itemView.performClick());
                var toolbar = (androidx.appcompat.widget.Toolbar) activity.findViewById(R.id.toolbar);
                assertEquals(activity.getString(R.string.device_detail_title), toolbar.getTitle().toString());
                assertNull(activity.findViewById(R.id.history_session_results));
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertSame(list, activity.findViewById(R.id.history_session_results));
                assertFalse(((android.widget.CompoundButton) activity.findViewById(R.id.history_candidates_only)).isChecked());
                activity.closeSettingsPage();
                assertNull(activity.findViewById(R.id.history_session_results));
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            context.deleteDatabase(database + ".db");
            context.deleteSharedPreferences(database);
        }
    }

    private void awaitCardCount(android.app.Instrumentation instrumentation, ScannerActivity activity, int expected) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5_000;
        var count = new java.util.concurrent.atomic.AtomicInteger();
        do {
            instrumentation.runOnMainSync(() -> {
                var list = (androidx.recyclerview.widget.RecyclerView) activity.findViewById(R.id.history_session_results);
                count.set(list.getAdapter().getItemCount());
            });
            if (count.get() == expected) return;
            android.os.SystemClock.sleep(50);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        assertEquals(expected, count.get());
    }

    private void awaitStartup(android.app.Instrumentation instrumentation, ScannerActivity activity) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5_000;
        var splash = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            instrumentation.runOnMainSync(() -> splash.set(activity.splashShowing()));
            if (!splash.get()) return;
            android.os.SystemClock.sleep(50);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        assertFalse(splash.get());
    }

    private <T> T await(Consumer<ScanHistory.Callback<T>> operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<T> output = new AtomicReference<>();
        operation.accept(value -> { output.set(value); ready.countDown(); });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        return output.get();
    }
}
