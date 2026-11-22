package com.uplusys.deviceguard;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public class DetectionPolicyTest {
    private SavedTargets.Snapshot rules(DetectionRule... rules) {
        return new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of(), Set.of(), List.of(rules));
    }

    private DetectionRule user(String id, DeviceCategory category, DetectionRule.Source source,
                               DetectionRule.Field field, String value, boolean alerts) {
        return new DetectionRule(id, category, source, field, value, true, alerts,
                DetectionRule.EvidenceLevel.USER, "시험 등록", "");
    }

    private SessionConfig config(Set<DeviceCategory> categories, DetectionRule... rules) {
        return SessionConfig.create(categories, rules(rules), RadioScanner.Mode.ALL, false);
    }

    private RadioScanner.Signal signal(RadioScanner.Mode source, String name) {
        return new RadioScanner.Signal(source, "00:11:22:33:44:55", name, -50, Set.of(), Set.of(), Set.of());
    }

    @Test public void allFourSupportedTypesAreSelectedByDefault() {
        assertEquals(Set.of(DeviceCategory.GLASSES, DeviceCategory.CAMERA, DeviceCategory.AUDIO, DeviceCategory.WATCH),
                DeviceCategory.defaults());
        assertFalse(DeviceCategory.CUSTOM.selectable());
        assertFalse(DeviceCategory.UNKNOWN.selectable());
        assertThrows(IllegalArgumentException.class, () -> config(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> config(Set.of(DeviceCategory.CUSTOM)));
    }

    @Test public void automaticPathsChooseCameraWifiFirstAndIncludeTypedRuleSource() {
        assertEquals(List.of(RadioScanner.Mode.WIFI, RadioScanner.Mode.BLE, RadioScanner.Mode.CLASSIC),
                SessionConfig.automaticPaths(DeviceCategory.defaults(), rules()));
        assertEquals(List.of(RadioScanner.Mode.BLE, RadioScanner.Mode.CLASSIC),
                SessionConfig.automaticPaths(Set.of(DeviceCategory.GLASSES), rules()));
        var withoutPairingNames = new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of(),
                DeviceCatalog.PUBLISHED_ENTRIES.stream()
                        .filter(entry -> entry.category() == DeviceCategory.GLASSES
                                && entry.paths().contains(RadioScanner.Mode.CLASSIC))
                        .map(DeviceCatalog.Entry::label).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of(RadioScanner.Mode.BLE),
                SessionConfig.automaticPaths(Set.of(DeviceCategory.GLASSES), withoutPairingNames));
        assertEquals(List.of(RadioScanner.Mode.WIFI, RadioScanner.Mode.BLE),
                SessionConfig.automaticPaths(Set.of(DeviceCategory.CAMERA), rules()));
        assertEquals(List.of(RadioScanner.Mode.BLE, RadioScanner.Mode.CLASSIC),
                SessionConfig.automaticPaths(Set.of(DeviceCategory.AUDIO), rules()));
        DetectionRule custom = user("ap", DeviceCategory.WATCH, DetectionRule.Source.WIFI,
                DetectionRule.Field.SSID, "Known Watch AP", false);
        assertEquals(List.of(RadioScanner.Mode.BLE, RadioScanner.Mode.WIFI),
                SessionConfig.automaticPaths(Set.of(DeviceCategory.WATCH), rules(custom)));
    }

    @Test public void ssidRegistrationUsesExactCaseAndWhitespaceAndOnlyWifi() {
        DetectionRule camera = user("camera", DeviceCategory.CAMERA, DetectionRule.Source.WIFI,
                DetectionRule.Field.SSID, " Camera_AP ", true);
        assertFalse(camera.ignoreCase());
        assertTrue(camera.matches(signal(RadioScanner.Mode.WIFI, " Camera_AP ")));
        assertFalse(camera.matches(signal(RadioScanner.Mode.WIFI, " camera_ap ")));
        assertFalse(camera.matches(signal(RadioScanner.Mode.WIFI, "Camera_AP")));
        assertFalse(camera.matches(signal(RadioScanner.Mode.BLE, " Camera_AP ")));
        assertTrue(DeviceMatcher.alertEligible(DeviceMatcher.match(signal(RadioScanner.Mode.WIFI, " Camera_AP "),
                config(Set.of(DeviceCategory.CAMERA), camera))));
        assertTrue(DeviceMatcher.match(signal(RadioScanner.Mode.WIFI, " Camera_AP "),
                config(Set.of(DeviceCategory.GLASSES), camera)).isEmpty());
    }

    @Test public void publishedCameraRuleRequiresCurrentWifiAndRespectsCatalogDeletion() {
        String label = "RunCam 3S";
        String ssid = "RC3S_A1b2C3";
        var cameraConfig = config(Set.of(DeviceCategory.CAMERA));
        var matches = DeviceMatcher.match(signal(RadioScanner.Mode.WIFI, ssid), cameraConfig);
        assertEquals(List.of("builtin/" + label), matches.stream().map(DeviceMatcher.Match::ruleId).toList());
        assertEquals(ssid, matches.get(0).evidence().get(0).received());
        assertTrue(DeviceMatcher.alertEligible(matches));
        assertTrue(DeviceMatcher.match(signal(RadioScanner.Mode.WIFI, "RC3S_A1b2C"),
                cameraConfig).isEmpty());
        assertTrue(DeviceMatcher.match(signal(RadioScanner.Mode.BLE, ssid), cameraConfig).isEmpty());
        assertTrue(DeviceMatcher.match(signal(RadioScanner.Mode.WIFI, ssid),
                config(Set.of(DeviceCategory.WATCH))).isEmpty());
        var stale = new RadioScanner.Signal(RadioScanner.Mode.WIFI, "00:11:22:33:44:55", ssid, -50,
                Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), null, null,
                RadioScanner.NameOrigin.WIFI_SSID, 500, null, 2412, null, true);
        assertTrue(DeviceMatcher.match(stale, cameraConfig).isEmpty());
        var removed = new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of(), Set.of(label));
        assertTrue(DeviceMatcher.match(signal(RadioScanner.Mode.WIFI, ssid),
                SessionConfig.create(Set.of(DeviceCategory.CAMERA), removed, RadioScanner.Mode.ALL, false)).isEmpty());
    }

    @Test public void knownTypeConflictSuppressesExplicitUserAlerts() {
        DetectionRule camera = user("camera", DeviceCategory.CAMERA, DetectionRule.Source.BLE,
                DetectionRule.Field.BLE_ADVERTISED_NAME, "Dual Device", true);
        DetectionRule watch = user("watch", DeviceCategory.WATCH, DetectionRule.Source.BLE,
                DetectionRule.Field.BLE_ADVERTISED_NAME, "Dual Device", true);
        var matches = DeviceMatcher.match(signal(RadioScanner.Mode.BLE, "Dual Device"),
                config(Set.of(DeviceCategory.CAMERA, DeviceCategory.WATCH), camera, watch));
        assertEquals(2, matches.size());
        assertTrue(DeviceMatcher.conflict(matches));
        assertFalse(DeviceMatcher.alertEligible(matches));
    }

    @Test public void observedProductNamesCanAlertWhileBroadCluesRemainInformational() {
        var airpods = DeviceMatcher.match(signal(RadioScanner.Mode.CLASSIC, "AirPods Pro"), config(Set.of(DeviceCategory.AUDIO)));
        assertTrue(airpods.stream().anyMatch(match -> match.category() == DeviceCategory.AUDIO && match.classified()));
        assertTrue(airpods.stream().allMatch(match -> match.evidenceLevel() == DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT));
        assertTrue(DeviceMatcher.alertEligible(airpods));
        assertTrue(DeviceMatcher.match(signal(RadioScanner.Mode.CLASSIC, "AirPods Pro"),
                config(Set.of(DeviceCategory.GLASSES))).isEmpty());
        assertTrue(DeviceMatcher.match(signal(RadioScanner.Mode.WIFI, "CAM_HD_Mini"), config(Set.of(DeviceCategory.CAMERA))).isEmpty());
        var tapo = DeviceMatcher.match(signal(RadioScanner.Mode.WIFI, "Tapo_Cam_1234"), config(Set.of(DeviceCategory.CAMERA)));
        assertFalse(tapo.isEmpty());
        assertTrue(tapo.stream().noneMatch(DeviceMatcher.Match::classified));
        assertFalse(DeviceMatcher.alertEligible(tapo));
    }

    @Test public void sharedCompanyAndSharedServicesCannotAuthorizeAlarms() {
        RadioScanner.Signal company = new RadioScanner.Signal(RadioScanner.Mode.BLE, "00:11:22:33:44:55", null,
                -50, Set.of(0x03C2, 0x004C, 0x0075), Set.of(), Set.of());
        var matches = DeviceMatcher.match(company, config(Set.of(DeviceCategory.GLASSES, DeviceCategory.WATCH, DeviceCategory.AUDIO)));
        assertTrue(matches.stream().noneMatch(DeviceMatcher.Match::classified));
        assertFalse(DeviceMatcher.alertEligible(matches));
        DetectionRule manufacturer = user("company", DeviceCategory.WATCH, DetectionRule.Source.BLE,
                DetectionRule.Field.MANUFACTURER_ID, "0x0075", true);
        assertFalse(manufacturer.alerts());
        DetectionRule shared = user("uart", DeviceCategory.WATCH, DetectionRule.Source.BLE,
                DetectionRule.Field.ADVERTISED_UUID, DeviceCatalog.G1_UART_UUID, true);
        assertFalse(shared.alerts());
        assertFalse(shared.classifying());
        for (String uuid : List.of("00001812-0000-1000-8000-00805f9b34fb", DeviceCatalog.FRAME_UUID,
                "0000fdf0-0000-1000-8000-00805f9b34fb")) {
            DetectionRule generic = user("generic", DeviceCategory.AUDIO, DetectionRule.Source.BLE,
                    DetectionRule.Field.ADVERTISED_UUID, uuid, true);
            assertFalse(generic.alerts());
            assertFalse(generic.classifying());
        }
    }

    @Test public void cachedBleNameIsDistinctFromAnObservedAdvertisement() {
        RadioScanner.Signal cached = new RadioScanner.Signal(RadioScanner.Mode.BLE, "00:11:22:33:44:55", "Glasses_4097",
                -50, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), null, "Glasses_4097",
                RadioScanner.NameOrigin.CACHE, 500, null, null, null, false);
        assertTrue(DeviceMatcher.match(cached, config(DeviceCategory.defaults())).isEmpty());
        assertTrue(DeviceMatcher.alertEligible(DeviceMatcher.match(signal(RadioScanner.Mode.BLE, "Glasses_4097"),
                config(Set.of(DeviceCategory.GLASSES)))));
        DetectionRule precise = user("name", DeviceCategory.CAMERA, DetectionRule.Source.BLE,
                DetectionRule.Field.BLE_ADVERTISED_NAME, "Glasses_4097", true);
        assertFalse(precise.matches(cached));
        DetectionRule cache = user("cache", DeviceCategory.CAMERA, DetectionRule.Source.BLE,
                DetectionRule.Field.DEVICE_NAME, "Glasses_4097", true);
        assertTrue(cache.matches(cached));
        assertFalse(cache.alerts());
        assertFalse(cache.classifying());
    }

    @Test public void cachedClassicNameCannotSatisfyAnObservedNameRule() {
        RadioScanner.Signal cached = new RadioScanner.Signal(RadioScanner.Mode.CLASSIC, "00:11:22:33:44:55", "AirPods Pro",
                -50, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), null, "AirPods Pro",
                RadioScanner.NameOrigin.CACHE, 500, null, null, null, false);
        DetectionRule precise = user("classic", DeviceCategory.AUDIO, DetectionRule.Source.CLASSIC,
                DetectionRule.Field.CLASSIC_NAME, "AirPods Pro", true);
        assertFalse(precise.matches(cached));
        assertTrue(DeviceMatcher.match(cached, config(Set.of(DeviceCategory.AUDIO), precise)).isEmpty());
        assertTrue(precise.matches(signal(RadioScanner.Mode.CLASSIC, "AirPods Pro")));
    }

    @Test public void serviceDataEvidenceNeverClaimsAnAdvertisedServiceUuid() {
        RadioScanner.Signal dataOnly = new RadioScanner.Signal(RadioScanner.Mode.BLE, "00:11:22:33:44:55", null,
                -50, Set.of(), Set.of(), Set.of(DeviceCatalog.G2_UUID));
        var matches = DeviceMatcher.match(dataOnly, config(Set.of(DeviceCategory.GLASSES)));
        assertTrue(matches.stream().flatMap(match -> match.evidence().stream())
                .anyMatch(evidence -> evidence.type().equals("서비스 데이터 키 일치")));
        assertFalse(DeviceMatcher.alertEligible(matches));
        assertTrue(matches.stream().flatMap(match -> match.evidence().stream())
                .noneMatch(evidence -> evidence.type().contains("광고 서비스 UUID")));
        DetectionRule advertised = user("uuid", DeviceCategory.GLASSES, DetectionRule.Source.BLE,
                DetectionRule.Field.ADVERTISED_UUID, DeviceCatalog.G2_UUID, true);
        DetectionRule data = user("key", DeviceCategory.GLASSES, DetectionRule.Source.BLE,
                DetectionRule.Field.SERVICE_DATA_KEY, DeviceCatalog.G2_UUID, true);
        assertFalse(advertised.matches(dataOnly));
        assertTrue(data.matches(dataOnly));
    }

    @Test public void theSameUuidInTwoBleFieldsDoesNotQualifyForWarnings() {
        var config = config(Set.of(DeviceCategory.GLASSES));
        for (String uuid : List.of(DeviceCatalog.FRAME_UUID, DeviceCatalog.G2_UUID, DeviceCatalog.HEYCYAN_UUID)) {
            var signal = new RadioScanner.Signal(RadioScanner.Mode.BLE, "00:11:22:33:44:55", null,
                    -50, Set.of(), Set.of(uuid), Set.of(uuid));
            var matches = DeviceMatcher.match(signal, config);
            assertTrue(matches.stream().anyMatch(match -> match.classified() && match.evidence().size() == 2));
            assertFalse("Duplicate UUID must not authorize a warning: " + uuid, DeviceMatcher.alertEligible(matches));
        }
    }

    @Test public void rokidRequiresDistinctServiceIdentifiersForNamelessWarnings() {
        var config = config(Set.of(DeviceCategory.GLASSES));
        var duplicate = new RadioScanner.Signal(RadioScanner.Mode.BLE, "00:11:22:33:44:55", null, -50,
                Set.of(), Set.of(DeviceCatalog.ROKID_DISCOVERY_UUID), Set.of(DeviceCatalog.ROKID_DISCOVERY_UUID));
        assertFalse(DeviceMatcher.alertEligible(DeviceMatcher.match(duplicate, config)));
        var corroborated = new RadioScanner.Signal(RadioScanner.Mode.BLE, "00:11:22:33:44:55", null, -50,
                Set.of(), Set.of(DeviceCatalog.ROKID_DISCOVERY_UUID), Set.of(DeviceCatalog.ROKID_SERVICE_DATA_KEY));
        assertTrue(DeviceMatcher.alertEligible(DeviceMatcher.match(corroborated, config)));
    }

    @Test public void cameraFeatureTagsRequireSpecificProductNameEvidence() {
        var named = DeviceMatcher.match(signal(RadioScanner.Mode.BLE, "Solos Vision ABCD"),
                config(Set.of(DeviceCategory.GLASSES)));
        assertTrue(named.stream().anyMatch(match -> match.featureTags().contains("카메라 내장")));
        RadioScanner.Signal familyOnly = new RadioScanner.Signal(RadioScanner.Mode.BLE, "00:11:22:33:44:55", null,
                -50, Set.of(), Set.of(DeviceCatalog.FRAME_UUID), Set.of());
        assertTrue(DeviceMatcher.match(familyOnly, config(Set.of(DeviceCategory.GLASSES))).stream()
                .allMatch(match -> match.featureTags().isEmpty()));
        assertTrue(DeviceMatcher.match(signal(RadioScanner.Mode.BLE, "Solos AirGo 3 1234"),
                config(Set.of(DeviceCategory.GLASSES))).stream().allMatch(match -> match.featureTags().isEmpty()));
    }

    @Test public void legacyRulesRetainBehaviorWithoutOptingInAlarmsAndSnapshotsAreImmutable() {
        SavedTargets.Snapshot snapshot = new SavedTargets.Snapshot(Set.of("MiXeD"), Set.of(DeviceCatalog.G2_UUID), Set.of("0x03C2"));
        assertEquals(3, snapshot.rules().size());
        assertTrue(snapshot.rules().stream().allMatch(rule -> rule.category() == DeviceCategory.CUSTOM && !rule.alerts()));
        assertTrue(snapshot.rules().stream().anyMatch(rule -> rule.matches(signal(RadioScanner.Mode.WIFI, "mixed"))));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.rules().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.names().clear());
    }
}
