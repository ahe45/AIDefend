package com.glassesscanner;

import android.content.Context;
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
        try (ScanHistory first = new ScanHistory(testContext, storageName)) {
            ScanHistory.Detail legacy = await(callback -> first.detail("legacy-0", callback));
            assertNotNull(legacy);
            assertEquals("old body", legacy.observations());

            first.begin("finished", 100, "BLE");
            RadioScanner.Signal signal = new RadioScanner.Signal(RadioScanner.Mode.BLE, "01:23:45:67:89:AB",
                    "Vuzix Z100", -60, Collections.singleton(0x060C), Collections.emptySet(),
                    Collections.singleton(DeviceCatalog.G2_UUID), Collections.singletonMap(0x060C, "0123ABCD"),
                    Collections.singletonMap(DeviceCatalog.G2_UUID, "FF10"));
            ScanObservation observation = new ScanObservation(signal, Collections.emptyList());
            assertEquals(Boolean.TRUE, this.<Boolean>await(callback -> first.save("finished", 101, "completed",
                    "스캔 완료", 1, 0, 0, Collections.singletonList(observation), callback)));
            ScanHistory.Detail saved = await(callback -> first.detail("finished", callback));
            assertTrue(saved.observations().contains("0123ABCD"));
            assertTrue(saved.observations().contains("FF10"));
            assertEquals(Boolean.FALSE, this.<Boolean>await(callback -> first.save("finished", 102, "stopped",
                    "스캔 중지", 0, 0, 0, Collections.emptyList(), callback)));

            first.begin("interrupted", 103, "BLE");
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

    private <T> T await(Consumer<ScanHistory.Callback<T>> operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<T> output = new AtomicReference<>();
        operation.accept(value -> { output.set(value); ready.countDown(); });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        return output.get();
    }
}
