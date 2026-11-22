package com.uplusys.deviceguard;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DocumentedWearables {
    private static final String POLAR_MODELS = "https://github.com/polarofficial/polar-ble-sdk/blob/master/sources/Android/android-communications/library/src/main/assets/polar_device_capabilities.json";

    private DocumentedWearables() {}

    static List<DeviceCatalog.Entry> entries() {
        return List.of(
                earbud("Sony WF-1000XM4", "WF-1000XM4",
                        "https://helpguide.sony.net/mdr/wf1000xm4/v1/en/contents/TP1000240524.html"),
                earbud("Sony WF-1000XM6", "WF-1000XM6",
                        "https://helpguide.sony.net/mdr/2985/v1/en/contents/F032_Pairing_and_connecting_with_a_Bluetooth_device.html"),
                earbud("Sony WF-C510", "WF-C510",
                        "https://helpguide.sony.net/mdr/2982/v1/en/contents/TP1001611704.html"),
                earbud("Sony WF-C700N", "WF-C700N",
                        "https://helpguide.sony.net/mdr/2968/v1/en/contents/TP1000781425.html"),
                earbud("Sony WF-C710N", "WF-C710N",
                        "https://helpguide.sony.net/mdr/2986/v1/en/contents/TP1001827211.html"),
                earbud("Sony LinkBuds", "LinkBuds", "https://helpguide.sony.net/mdr/linkbuds/v1/en/print.pdf"),
                earbud("Sony LinkBuds Fit", "LinkBuds Fit",
                        "https://helpguide.sony.net/mdr/2975/v1/en/contents/TP1001611704.html"),
                earbud("Sony LinkBuds Open", "LinkBuds Open",
                        "https://helpguide.sony.net/mdr/2964/v1/en/contents/TP1001298195.html"),
                earbud("Sony LinkBuds Clip", "LinkBuds Clip", "https://helpguide.sony.net/mdr/2999/v1/en/print.pdf"),
                earbud("Jabra Elite 8 Active", "Jabra Elite 8 Active",
                        "https://www.jabra.com/support/export/faq?groupid=2171&id=36e9fed9-b39c-4997-8aee-c80c4e9e3616&pdf=1"),
                earbud("Jabra Elite 8 Active Gen 2", "Jabra Elite 8 Active Gen 2",
                        "https://www.jabra.com/support/export/faq?groupid=2302&id=821d8579-6a97-4565-87bf-cb06030a95cc&pdf=1"),
                earbud("Jabra Elite 10", "Jabra Elite 10",
                        "https://www.jabra.com/support/export/faq?groupid=2137&id=97ddbfdb-57c1-47ac-bb61-0859c36622b1&pdf=1"),
                earbud("Jabra Elite 10 Gen 2", "Jabra Elite 10 Gen 2",
                        "https://www.jabra.com/support/export/faq?groupid=2295&id=fc413d47-3ed8-44c5-ba55-4bb484f8eac2&pdf=1"),
                earbud("Sennheiser MOMENTUM True Wireless 4", "MOMENTUM TW 4",
                        "https://uk.sennheiser-hearing.com/pages/mtw4-faq/"),
                earbud("REDMI Buds 6", "REDMI Buds 6", "https://www.mi.com/global/support/faq/details/KA-511267/"),
                earbud("REDMI Buds 6 Pro", "REDMI Buds 6 Pro", "https://www.mi.com/global/support/faq/details/KA-517231/"),
                earbud("REDMI Buds 6 Active", "REDMI Buds 6 Active", "https://www.mi.com/global/support/faq/details/KA-484824/"),
                earbud("REDMI Buds 6 Play", "REDMI Buds 6 Play", "https://www.mi.com/global/support/faq/details/KA-621679/"),
                earbud("Shokz OpenFit", "OpenFit by Shokz",
                        "https://asia.help.shokz.com/s/article/How-to-connect-the-Shokz-App-with-OpenFitASIA35?language=en_US"),
                earbud("Shokz OpenFit 2", "OpenFit 2 by Shokz", "https://intl.help.shokz.com/s/article/How-do-I-pair-OpenFit-2-with-my-device"),
                earbud("Shokz OpenFit Air 2", "OpenFit Air 2 by Shokz", "https://shokz.com/products/openfit-air-2"),
                DeviceCatalog.bleName("Pine64 PineTime", "PineTime · InfiniTime 펌웨어", "^InfiniTime$",
                        "InfiniTime 펌웨어의 BLE 검색 응답 이름 InfiniTime 전체 일치 · 실기 검증 전",
                        "https://github.com/InfiniTimeOrg/InfiniTime/blob/main/src/components/ble/NimbleController.cpp#L147-L164",
                        DeviceCategory.WATCH, DetectionRule.EvidenceLevel.OFFICIAL_TECHNICAL),
                DeviceCatalog.bleName("Espruino Bangle.js 1·2", "Bangle.js 1 · Bangle.js 2", "^Bangle\\.js [A-F0-9]{4}$",
                        "BLE 광고 이름 Bangle.js와 MAC 주소 끝 4자리 형식 일치 · 실기 검증 전",
                        "https://www.espruino.com/BLE%20Emoji", DeviceCategory.WATCH, DetectionRule.EvidenceLevel.OFFICIAL_TECHNICAL),
                DeviceCatalog.bleName("Amazfit Bip 6", "Bip 6", "^Amazfit Bip 6$",
                        "공개 스캔 표본의 BLE 광고 이름 Amazfit Bip 6 전체 일치 · 실기 재현 전",
                        "https://community.jeedom.com/t/dependances-ne-sinstallent-pas-sous-debian-12/147018",
                        DeviceCategory.WATCH, DetectionRule.EvidenceLevel.CLUE),
                polarWatch("Ignite 3"),
                polarWatch("Vantage V3"),
                polarWatch("Vantage M3"),
                polarWatch("Grit X2"),
                polarWatch("Grit X2 Pro")
        );
    }

    private static DeviceCatalog.Entry earbud(String label, String name, String source) {
        return DeviceCatalog.bluetoothName(label, label, name, DeviceCategory.AUDIO,
                "공식 연결 안내의 페어링 검색명 " + name + " 전체 일치 · 실기 검증 전", source);
    }

    private static DeviceCatalog.Entry polarWatch(String model) {
        String label = "Polar " + model;
        Pattern pattern = Pattern.compile(Pattern.quote(label) + " ([0-9A-F]{7,8})", Pattern.CASE_INSENSITIVE);
        return DeviceCatalog.published(label, model, DeviceCategory.WATCH, DetectionRule.EvidenceLevel.OFFICIAL_TECHNICAL,
                "BLE 광고 이름 " + label + "과 16진수 기기 ID 7~8자리 일치 · 실기 검증 전", POLAR_MODELS, signal -> {
                    String observed = signal.advertisedName();
                    if (observed == null) return List.of();
                    Matcher match = pattern.matcher(observed);
                    if (!match.matches()) return List.of();
                    String deviceId = match.group(1);
                    if (deviceId.length() == 8 && !validFullPolarId(deviceId)) return List.of();
                    return List.of(new DeviceMatcher.Evidence(DetectionRule.Field.BLE_ADVERTISED_NAME.label,
                            observed, "Polar SDK의 모델명과 기기 ID 형식 일치"));
                });
    }

    // Polar SDK removes the checksum digit in shortened 7-character advertisement IDs.
    // For full IDs, compare the last digit against the SDK's alternating weighted sum.
    private static boolean validFullPolarId(String deviceId) {
        int sum = 0;
        for (int index = 0; index < 7; index++) {
            int digit = Character.digit(deviceId.charAt(index), 16);
            sum += digit * (index % 2 == 0 ? 3 : 1);
        }
        return sum % 16 == Character.digit(deviceId.charAt(7), 16);
    }
}
