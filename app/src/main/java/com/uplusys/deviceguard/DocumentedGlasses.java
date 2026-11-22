package com.uplusys.deviceguard;

import java.util.List;
import java.util.Map;

final class DocumentedGlasses {
    private static final String MONOCLE_REPL_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
    private static final int ACTIVELOOK_MANUFACTURER_ID = 0xDAFA;
    private static final String ACTIVELOOK_DEFAULT_DATA = "08F2";

    private DocumentedGlasses() {}

    static List<DeviceCatalog.Entry> entries() {
        return List.of(
                DeviceCatalog.published("Xingyi AR99", "Xingyi Intelligent AR99",
                        DeviceCategory.GLASSES, DetectionRule.EvidenceLevel.OFFICIAL_TECHNICAL,
                        "BLE 제조사 데이터의 AR99 헤더와 스캔 응답 길이 조건",
                        "https://github.com/Mentra-Community/MentraOS/blob/12683aa66a3c7dc39b4b0c4eaf73ad1d64bfb5dc/mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/sgcs/Ar99.java#L2394-L2444",
                        DocumentedGlasses::matchAr99),
                DeviceCatalog.published("Brilliant Labs Monocle", "Monocle",
                        DeviceCategory.GLASSES, DetectionRule.EvidenceLevel.OFFICIAL_TECHNICAL,
                        "BLE 광고 이름 monocle과 광고된 REPL 서비스 UUID가 함께 일치",
                        "https://github.com/brilliantlabsAR/monocle-micropython/blob/main/main.c#L687-L803",
                        DocumentedGlasses::matchMonocle),
                DeviceCatalog.published("ActiveLook 안경 계열", "ENGO 2 · Julbo EVAD-1 등",
                        DeviceCategory.GLASSES, DetectionRule.EvidenceLevel.OFFICIAL_TECHNICAL,
                        "BLE 광고의 기본 제조사 데이터 FA DA 08 F2 전체 일치",
                        "https://github.com/ActiveLook/Activelook-API-Documentation/blob/main/ActiveLook_API.md#22-advertising",
                        DocumentedGlasses::matchActiveLook),
                DeviceCatalog.bluetoothName("JBL Soundgear Frames", "Round · Square",
                        "JBL Soundgear Frames", DeviceCategory.GLASSES,
                        "JBL 공식 안내의 Bluetooth 페어링 이름 전체 일치",
                        "https://support.jbl.com/on/demandware.static/-/Sites-masterCatalog_Harman/default/dwa671b841/pdfs/JBL_Soundgear%20Frames%20%28Square%29_Global_QSG.pdf"),
                DeviceCatalog.bluetoothName("JLab JBuds Frames", "JBuds Frames · 안경 장착형 오디오",
                        "JLab JBuds Frames", DeviceCategory.GLASSES,
                        "JLab 공식 안내의 Bluetooth 페어링 이름 전체 일치",
                        "https://help.jlab.com/pages/jbuds-frames-first-time-setup")
        );
    }

    private static List<DeviceMatcher.Evidence> matchAr99(RadioScanner.Signal signal) {
        if (signal.source() != RadioScanner.Mode.BLE) {
            return List.of();
        }
        // Android exposes the first two bytes of the manufacturer data as a little-endian ID.
        for (Map.Entry<Integer, String> data : signal.manufacturerData().entrySet()) {
            int prefix = data.getKey();
            if (prefix != 0x5241 && prefix != 0x7241 && prefix != 0x5261 && prefix != 0x7261) {
                continue;
            }
            String payload = data.getValue();
            if (payload.length() >= 36 && payload.length() % 2 == 0 && payload.startsWith("3939")) {
                return List.of(new DeviceMatcher.Evidence("BLE 제조사 데이터", "AR99",
                        "AR99 헤더와 제조사 데이터 20바이트 이상"));
            }
        }
        return List.of();
    }

    private static List<DeviceMatcher.Evidence> matchMonocle(RadioScanner.Signal signal) {
        if (signal.source() != RadioScanner.Mode.BLE || !"monocle".equals(signal.advertisedName())) {
            return List.of();
        }
        for (String uuid : signal.advertisedUuids()) {
            if (MONOCLE_REPL_UUID.equalsIgnoreCase(uuid)) {
                return List.of(
                        new DeviceMatcher.Evidence("BLE 광고 이름", signal.advertisedName(), "monocle"),
                        new DeviceMatcher.Evidence("BLE 광고 서비스 UUID", uuid, MONOCLE_REPL_UUID));
            }
        }
        return List.of();
    }

    private static List<DeviceMatcher.Evidence> matchActiveLook(RadioScanner.Signal signal) {
        if (signal.source() == RadioScanner.Mode.BLE && ACTIVELOOK_DEFAULT_DATA.equalsIgnoreCase(
                signal.manufacturerData().get(ACTIVELOOK_MANUFACTURER_ID))) {
            return List.of(new DeviceMatcher.Evidence("BLE 제조사 데이터", "FA DA 08 F2",
                    "ActiveLook 기본 제조사 데이터 전체 일치"));
        }
        return List.of();
    }
}
