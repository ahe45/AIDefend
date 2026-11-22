package com.uplusys.deviceguard;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TypedTargetsStorageTest {
    @Test public void legacyRegistrationMigrationAndNarrowedEditPersistOnce() throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "typed_targets_test_" + UUID.randomUUID();
        Context isolated = new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) {
                return super.getSharedPreferences(name, mode);
            }
        };
        try {
            SharedPreferences preferences = isolated.getSharedPreferences("saved_targets", Context.MODE_PRIVATE);
            DetectionSettings settings = new DetectionSettings(isolated);
            assertEquals(Set.of(DeviceCategory.GLASSES, DeviceCategory.CAMERA, DeviceCategory.AUDIO, DeviceCategory.WATCH),
                    settings.selected());
            assertTrue(preferences.edit().putStringSet("device_names", Set.of("LegacyName"))
                    .putStringSet("service_uuids", Set.of(DeviceCatalog.G2_UUID))
                    .putStringSet("categories", Set.of("CUSTOM", "WATCH")).commit());
            assertEquals(Set.of(DeviceCategory.WATCH), settings.selected());
            assertTrue(preferences.edit().putStringSet("categories", Set.of("CUSTOM")).commit());
            assertEquals(DeviceCategory.defaults(), settings.selected());
            settings.apply(Set.of(DeviceCategory.GLASSES));
            SavedTargets store = new SavedTargets(isolated);
            assertEquals(2, store.rules().size());
            assertEquals(2, new SavedTargets(isolated).rules().size());
            DetectionRule legacy = store.rules().stream().filter(rule -> rule.field() == DetectionRule.Field.LEGACY_NAME)
                    .findFirst().orElseThrow();
            assertFalse(legacy.alerts());
            assertEquals(DetectionRule.Source.ANY, legacy.source());
            assertEquals("종류 미지정", legacy.category().label);
            assertThrows(IllegalArgumentException.class, () -> store.saveRule(legacy));
            var signal = new RadioScanner.Signal(RadioScanner.Mode.BLE, "test-device",
                    "LegacyName", -50, Set.of(), Set.of(), Set.of());
            assertTrue(DeviceMatcher.match(signal, settings.snapshot(store.snapshot(), RadioScanner.Mode.ALL)).isEmpty());
            DetectionRule narrowed = new DetectionRule(legacy.id(), DeviceCategory.CAMERA, DetectionRule.Source.WIFI,
                    DetectionRule.Field.SSID, "ExactCamera", false, true, DetectionRule.EvidenceLevel.USER, "", "");
            store.saveRule(narrowed);
            assertTrue(store.values(false).isEmpty());
            assertEquals(2, new SavedTargets(isolated).rules().size());
            SessionConfig frozen = SessionConfig.create(Set.of(DeviceCategory.CAMERA), store.snapshot(), RadioScanner.Mode.ALL, true);
            var ap = new RadioScanner.Signal(RadioScanner.Mode.WIFI, "test-ap", "ExactCamera", -50,
                    Set.of(), Set.of(), Set.of());
            assertTrue(DeviceMatcher.match(ap, settings.snapshot(store.snapshot(), RadioScanner.Mode.ALL)).isEmpty());
            assertEquals(DeviceCategory.CAMERA, DeviceMatcher.match(ap, frozen).get(0).category());
            SessionConfig restored = SessionConfig.fromJson(frozen.toJson());
            assertEquals(frozen, restored);
            store.deleteRule(narrowed.id());
            assertEquals(1, new SavedTargets(isolated).rules().size());
            assertEquals(2, frozen.rules().rules().size());
            assertEquals(1, restored.rules().rules().stream().filter(DetectionRule::alerts).count());
            store.change(false, "LegacyAfterMigration", true);
            assertEquals(2, new SavedTargets(isolated).rules().size());
            store.change(false, "LegacyAfterMigration", false);
            assertEquals(1, new SavedTargets(isolated).rules().size());
        } finally {
            base.deleteSharedPreferences(name);
        }
    }
}
