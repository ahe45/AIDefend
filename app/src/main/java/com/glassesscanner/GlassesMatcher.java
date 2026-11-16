package com.glassesscanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static com.glassesscanner.DeviceCatalog.FRAME_UUID;
import static com.glassesscanner.DeviceCatalog.G1_UART_UUID;
import static com.glassesscanner.DeviceCatalog.G2_UUID;
import static com.glassesscanner.DeviceCatalog.HEYCYAN_UUID;
import static com.glassesscanner.DeviceCatalog.ROKID_DISCOVERY_UUID;
import static com.glassesscanner.DeviceCatalog.ROKID_SERVICE_DATA_KEY;

final class GlassesMatcher {
    record Match(String label, String reason, int strength) {}


    static List<Match> classify(String name, Set<Integer> companyIds, Set<String> advertisedUuids, Set<String> serviceDataKeys) {
        String value = normalize(name);
        List<Match> matches = new ArrayList<>();
        boolean rokidDiscovery = advertisedUuids.contains(ROKID_DISCOVERY_UUID);
        boolean rokidCorroborated = rokidDiscovery && serviceDataKeys.contains(ROKID_SERVICE_DATA_KEY);
        if (rokidDiscovery) {
            matches.add(new Match("Rokid 계열 후보", rokidCorroborated
                    ? "근거 2개 · BLE 서비스 9100 + 서비스 데이터 키 9A01"
                    : "BLE 검색 서비스 9100", rokidCorroborated ? 2 : 1));
        }
        for (DeviceCatalog.Entry rule : DeviceCatalog.ENTRIES) {
            if (rule.matches(value)) {
                boolean g1Uart = rule.label().equals("Even Realities G1")
                        && (advertisedUuids.contains(G1_UART_UUID) || serviceDataKeys.contains(G1_UART_UUID));
                matches.add(new Match(rule.label() + " 후보",
                        rule.evidence().split("\n", 2)[0] + (g1Uart ? " · 이름 및 UART 서비스 일치" : ""), g1Uart ? 2 : 1));
                break;
            }
        }
        if (!rokidDiscovery && value.matches("^rokid(?:$|[ _-].*)")
                && matches.isEmpty()) {
            matches.add(new Match("Rokid 계열 후보", "브랜드 이름 · 세부 모델 미확인", 1));
        }
        if (advertisedUuids.contains(G2_UUID) || serviceDataKeys.contains(G2_UUID)) {
            boolean g2Name = value.matches("^even[ _-]+g2(?:$|[ _-].*)");
            matches.add(new Match("Even Realities G2 후보", g2Name
                    ? "이름 및 공개 역공학 서비스 UUID 일치" : "공개 역공학 서비스 UUID 일치 · 모델 검증 전",
                    g2Name ? 2 : 1));
        }
        if (companyIds.contains(0x060C)) {
            boolean vuzixName = value.startsWith("vuzix ") || value.startsWith("vuzix_") || value.startsWith("vuzix-");
            matches.add(new Match("Vuzix 계열 후보", vuzixName
                    ? "이름 및 Vuzix 제조사 ID 일치" : "Vuzix 제조사 ID · 모델 미확인", vuzixName ? 2 : 1));
        }

        boolean luxottica = companyIds.contains(0x0D53);
        boolean meta = companyIds.contains(0x01AB) || companyIds.contains(0x058E);
        String metaModel = value.matches("^(?:le[ _-]+)?ray[ _-]?ban(?:$|[ _-].*)")
                ? "Ray-Ban 계열" : value.matches("^(?:le[ _-]+)?oakley[ _-]+meta(?:$|[ _-].*)")
                ? "Oakley Meta" : null;
        if (metaModel != null) {
            matches.add(new Match(metaModel + " 후보", meta || luxottica
                    ? "제품명 및 Meta/Luxottica 제조사 ID" : "제품명 패턴", meta || luxottica ? 2 : 1));
        } else if (meta || luxottica) {
            matches.add(new Match("Meta/Luxottica 계열 후보", "제조사 ID 일치 · 모델 미확인", 1));
        }

        boolean spectacles = value.matches("^(?:snap[ _-]+)?spectacles(?:$|[ _-].*)");
        boolean snap = companyIds.contains(0x03C2);
        if (spectacles) {
            matches.add(new Match("Snap Spectacles 후보",
                    snap ? "Spectacles 이름 및 Snap 제조사 ID" : "Spectacles 이름", snap ? 2 : 1));
        } else if (snap) {
            matches.add(new Match("Snap 계열 후보", "Snap 제조사 ID · 모델 미확인", 1));
        }

        if ((advertisedUuids.contains(FRAME_UUID) || serviceDataKeys.contains(FRAME_UUID))) {
            matches.add(new Match("Brilliant Labs Frame 후보", "공개 SDK의 서비스 UUID 일치", 2));
        }
        boolean heycyanName = value.startsWith("heycyan");
        boolean heycyanUuid = advertisedUuids.contains(HEYCYAN_UUID) || serviceDataKeys.contains(HEYCYAN_UUID);
        if (heycyanName || heycyanUuid) {
            matches.add(new Match("HeyCyan 계열 후보", heycyanName && heycyanUuid
                    ? "이름 및 BLE 서비스 UUID" : heycyanName ? "HeyCyan 이름" : "기존 APK의 BLE 서비스 UUID",
                    heycyanName && heycyanUuid ? 2 : 1));
        }
        return matches;
    }

    static String[] bleServiceFilters() {
        return new String[]{ROKID_DISCOVERY_UUID, FRAME_UUID, HEYCYAN_UUID, G2_UUID};
    }

    static int[] bleCompanyFilters() {
        return new int[]{0x0D53, 0x01AB, 0x058E, 0x03C2, 0x060C};
    }

    static List<Match> classifyWifi(String ssid) {
        // A wireless product may also join a normal AP. Only user-registered AP names are classified.
        return new ArrayList<>();
    }

    private static String normalize(String name) {
        if (name == null) return "";
        return java.text.Normalizer.normalize(name.replace("™", "").replace("®", ""), java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{Z}\\s]+", " ").trim();
    }
}

