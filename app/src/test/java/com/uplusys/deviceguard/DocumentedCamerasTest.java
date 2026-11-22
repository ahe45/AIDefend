package com.uplusys.deviceguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class DocumentedCamerasTest {
    private static final String GOPRO_UUID = "0000fea6-0000-1000-8000-00805f9b34fb";
    private static final int GOPRO_COMPANY_ID = 0x02F2;
    private static final SessionConfig CONFIG = SessionConfig.create(Set.of(DeviceCategory.CAMERA),
            new SavedTargets.Snapshot(Set.of(), Set.of(), Set.of()), RadioScanner.Mode.ALL, false);

    @Test
    public void requiresFullCaseSensitiveWifiSsid() {
        assertEquals("Transcend DrivePro Body 30 의심 기기", wifi("DPB30_9528B8").get(0).label());
        assertEquals("SJCAM C110 의심 기기", wifi("C110_9803c7abced12").get(0).label());
        assertEquals("SJCAM SJ10 Pro Dual Screen 의심 기기", wifi("SJ10Pro DualScreen_X94M5C07").get(0).label());

        for (String mismatch : List.of("dpb30_9528B8", "DPB30_9528B8_EXT", "DPB30_9528BG", "C110_9803c",
                "C110_abcde7abced12", "SJ10Pro DualScreen_X94M5C0")) {
            assertTrue(mismatch, wifi(mismatch).isEmpty());
        }
        var bluetooth = new RadioScanner.Signal(RadioScanner.Mode.BLE, "", "DPB30_9528B8", -50,
                Set.of(), Set.of(), Set.of());
        assertTrue(DeviceMatcher.match(bluetooth, CONFIG).isEmpty());
    }

    @Test
    public void requiresGoProServiceAndValidManufacturerPayloadWithoutExposingSerial() {
        String schema2 = "020401010000000000000000";
        String schema3 = "030405c10000000000414200";
        for (String payload : List.of(schema2, schema3)) {
            var matches = goPro(Set.of(GOPRO_UUID), GOPRO_COMPANY_ID, payload);
            assertEquals(1, matches.size());
            var match = matches.get(0);
            assertEquals("GoPro Open GoPro 카메라 계열 의심 기기", match.label());
            assertEquals(2, match.evidence().size());
            assertFalse(match.evidence().toString().contains(payload));
            assertFalse(match.evidence().toString().contains("4142"));
        }

        assertTrue(goPro(Set.of(), GOPRO_COMPANY_ID, schema3).isEmpty());
        assertTrue(goPro(Set.of(GOPRO_UUID), 0xF202, schema3).isEmpty());
        assertTrue(goPro(Set.of(GOPRO_UUID), GOPRO_COMPANY_ID, schema3.substring(0, 22)).isEmpty());
        assertTrue(goPro(Set.of(GOPRO_UUID), GOPRO_COMPANY_ID, "010405c10000000000414200").isEmpty());
        assertTrue(goPro(Set.of(GOPRO_UUID), GOPRO_COMPANY_ID, "030400c10000000000414200").isEmpty());
        assertTrue(goPro(Set.of(GOPRO_UUID), GOPRO_COMPANY_ID, "030405810000000000414200").isEmpty());
    }

    private static List<DeviceMatcher.Match> wifi(String ssid) {
        var signal = new RadioScanner.Signal(RadioScanner.Mode.WIFI, "", ssid, -50, Set.of(), Set.of(), Set.of());
        return DeviceMatcher.match(signal, CONFIG);
    }

    private static List<DeviceMatcher.Match> goPro(Set<String> uuids, int company, String payload) {
        var signal = new RadioScanner.Signal(RadioScanner.Mode.BLE, "", "GoPro 1234", -50,
                Set.of(company), uuids, Set.of(), Map.of(company, payload), Map.of());
        return DeviceMatcher.match(signal, CONFIG);
    }
}
