package com.uplusys.deviceguard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static com.uplusys.deviceguard.DeviceCatalog.FRAME_UUID;
import static com.uplusys.deviceguard.DeviceCatalog.G1_UART_UUID;
import static com.uplusys.deviceguard.DeviceCatalog.G2_UUID;
import static com.uplusys.deviceguard.DeviceCatalog.HEYCYAN_UUID;
import static com.uplusys.deviceguard.DeviceCatalog.ROKID_DISCOVERY_UUID;
import static com.uplusys.deviceguard.DeviceCatalog.ROKID_SERVICE_DATA_KEY;

final class DeviceMatcher {
    record Evidence(String type, String received, String criterion) {}

    record Match(String label, String reason, int strength, List<Evidence> evidence, DeviceCategory category,
                 DetectionRule.EvidenceLevel evidenceLevel, String ruleId, String sourceUrl,
                 boolean alertAllowed, boolean classified) {
        Match { evidence = List.copyOf(evidence); }
        Match(String label, String reason, int strength) { this(label, reason, strength, List.of()); }
        Match(String label, String reason, int strength, List<Evidence> evidence) {
            this(label, reason, strength, evidence, DeviceCategory.GLASSES, DetectionRule.EvidenceLevel.LEGACY,
                    "legacy/" + label, "", false, true);
        }
        Match(String label, String reason, int strength, Evidence... evidence) {
            this(label, reason, strength, List.of(evidence));
        }
        List<String> featureTags() {
            if (category != DeviceCategory.GLASSES || !classified
                    || evidence.stream().noneMatch(item -> item.type().equals("이름 일치"))) return List.of();
            // Model specifications describe capability; the name match itself is still unverified.
            return Set.of("Brilliant Labs Frame 의심 기기", "Solos AirGo V 카메라 의심 기기", "Solos AirGo V2 의심 기기")
                    .contains(label) ? List.of("카메라 내장") : List.of();
        }
    }


    static List<Match> classify(String name, Set<Integer> companyIds, Set<String> advertisedUuids, Set<String> serviceDataKeys) {
        return classify(name, companyIds, advertisedUuids, serviceDataKeys, Set.of());
    }

    static List<Match> classify(String name, Set<Integer> companyIds, Set<String> advertisedUuids,
                                Set<String> serviceDataKeys, Set<String> deleted) {
        String value = normalize(name);
        List<Match> matches = new ArrayList<>();
        boolean rokidDiscovery = !deleted.contains("Rokid CXR 통신 계열") && advertisedUuids.contains(ROKID_DISCOVERY_UUID);
        boolean rokidCorroborated = rokidDiscovery && serviceDataKeys.contains(ROKID_SERVICE_DATA_KEY);
        if (rokidDiscovery) {
            matches.add(new Match("Rokid 계열 의심 기기", rokidCorroborated
                    ? "근거 2개 · BLE 서비스 9100 + 서비스 데이터 키 9A01"
                    : "BLE 검색 서비스 9100", rokidCorroborated ? 2 : 1,
                    rokidCorroborated ? List.of(uuidEvidence(ROKID_DISCOVERY_UUID, "Rokid 계열 검색 서비스"),
                            new Evidence("서비스 데이터 키 일치", ROKID_SERVICE_DATA_KEY, "Rokid 계열 서비스 데이터 키"))
                            : List.of(uuidEvidence(ROKID_DISCOVERY_UUID, "Rokid 계열 검색 서비스"))));
        }
        boolean knownName = false;
        for (DeviceCatalog.Entry rule : DeviceCatalog.LEGACY_ENTRIES) {
            if (rule.category() != DeviceCategory.GLASSES) continue;
            if (rule.matches(value)) {
                knownName = true;
                if (deleted.contains(rule.label())) break;
                boolean g1Uart = rule.label().equals("Even Realities G1")
                        && (advertisedUuids.contains(G1_UART_UUID) || serviceDataKeys.contains(G1_UART_UUID));
                List<Evidence> evidence = new ArrayList<>();
                evidence.add(nameEvidence(name, rule.label().equals("Glasses 이름 스마트글래스")
                        ? "기기 이름에 ‘Glasses’ 포함" : rule.label() + " 등록 이름 패턴"));
                if (g1Uart) evidence.addAll(serviceEvidence(G1_UART_UUID, advertisedUuids, serviceDataKeys,
                        "Even Realities G1 보조 UART 서비스"));
                matches.add(new Match(rule.label() + " 의심 기기",
                        rule.evidence().split("\n", 2)[0] + (g1Uart ? " · 이름 및 UART 서비스 일치" : ""),
                        g1Uart ? 2 : 1, evidence));
                break;
            }
        }
        if (!knownName && !deleted.contains("Rokid CXR 통신 계열")
                && !rokidDiscovery && value.matches("^rokid(?:$|[ _-].*)")
                && matches.isEmpty()) {
            matches.add(new Match("Rokid 계열 의심 기기", "브랜드 이름 · 세부 모델 미확인", 1,
                    nameEvidence(name, "기기 이름이 ‘Rokid’ 브랜드 패턴과 일치")));
        }
        if (!deleted.contains("Even Realities G2") && (advertisedUuids.contains(G2_UUID) || serviceDataKeys.contains(G2_UUID))) {
            boolean g2Name = value.matches("^even[ _-]+g2(?:$|[ _-].*)");
            List<Evidence> evidence = new ArrayList<>();
            if (g2Name) evidence.add(nameEvidence(name, "Even Realities G2 등록 이름 패턴"));
            evidence.addAll(serviceEvidence(G2_UUID, advertisedUuids, serviceDataKeys, "Even Realities G2 등록 서비스 식별자"));
            matches.add(new Match("Even Realities G2 의심 기기", g2Name
                    ? "이름 및 공개 역공학 서비스 UUID 일치" : "공개 역공학 서비스 UUID 일치 · 모델 검증 전",
                    g2Name ? 2 : 1, evidence));
        }
        if (!deleted.contains("Vuzix") && companyIds.contains(0x060C)) {
            boolean vuzixName = value.startsWith("vuzix ") || value.startsWith("vuzix_") || value.startsWith("vuzix-");
            matches.add(new Match("Vuzix 계열 의심 기기", vuzixName
                    ? "이름 및 Vuzix 제조사 ID 일치" : "Vuzix 제조사 ID · 모델 미확인", vuzixName ? 2 : 1,
                    vuzixName ? List.of(nameEvidence(name, "기기 이름이 ‘Vuzix’ 브랜드 패턴과 일치"),
                            companyEvidence(companyIds, Set.of(0x060C), "Vuzix 제조사 ID"))
                            : List.of(companyEvidence(companyIds, Set.of(0x060C), "Vuzix 제조사 ID"))));
        }

        boolean luxottica = companyIds.contains(0x0D53);
        boolean meta = companyIds.contains(0x01AB) || companyIds.contains(0x058E);
        String metaModel = value.matches("^(?:le[ _-]+)?ray[ _-]?ban(?:$|[ _-].*)")
                ? "Ray-Ban 계열" : value.matches("^(?:le[ _-]+)?oakley[ _-]+meta(?:$|[ _-].*)")
                ? "Oakley Meta" : null;
        if (metaModel != null) {
            String catalogLabel = metaModel.equals("Oakley Meta") ? "Oakley Meta" : "Ray-Ban / Meta";
            if (!deleted.contains(catalogLabel)) {
                List<Evidence> evidence = new ArrayList<>();
                evidence.add(nameEvidence(name, catalogLabel + " 등록 이름 패턴"));
                if (meta || luxottica) evidence.add(companyEvidence(companyIds, Set.of(0x0D53, 0x01AB, 0x058E),
                        "Meta/Luxottica 제조사 ID · 같은 제조사의 다른 기기도 포함 가능"));
                matches.add(new Match(metaModel + " 의심 기기", meta || luxottica
                        ? "제품명 및 Meta/Luxottica 제조사 ID" : "제품명 패턴", meta || luxottica ? 2 : 1, evidence));
            }
        } else if ((meta || luxottica) && (!deleted.contains("Ray-Ban / Meta") || !deleted.contains("Oakley Meta"))) {
            matches.add(new Match("Meta/Luxottica 계열 의심 기기", "제조사 ID 일치 · 모델 미확인", 1,
                    companyEvidence(companyIds, Set.of(0x0D53, 0x01AB, 0x058E),
                            "Meta/Luxottica 제조사 ID · 같은 제조사의 다른 기기도 포함 가능")));
        }

        boolean spectacles = !deleted.contains("Snap Spectacles") && value.matches("^(?:snap[ _-]+)?spectacles(?:$|[ _-].*)");
        boolean snap = !deleted.contains("Snap Spectacles") && companyIds.contains(0x03C2);
        if (spectacles) {
            List<Evidence> evidence = new ArrayList<>();
            evidence.add(nameEvidence(name, "Snap Spectacles 등록 이름 패턴"));
            if (snap) evidence.add(companyEvidence(companyIds, Set.of(0x03C2), "Snap 제조사 ID"));
            matches.add(new Match("Snap Spectacles 의심 기기",
                    snap ? "Spectacles 이름 및 Snap 제조사 ID" : "Spectacles 이름", snap ? 2 : 1, evidence));
        } else if (snap) {
            matches.add(new Match("Snap 계열 의심 기기", "Snap 제조사 ID · 모델 미확인", 1,
                    companyEvidence(companyIds, Set.of(0x03C2), "Snap 제조사 ID · 같은 제조사의 다른 기기도 포함 가능")));
        }

        if (!deleted.contains("Brilliant Labs Frame") && (advertisedUuids.contains(FRAME_UUID) || serviceDataKeys.contains(FRAME_UUID))) {
            matches.add(new Match("Brilliant Labs Frame / Halo 계열 의심 기기", "공개 SDK의 공통 서비스 UUID 일치 · 모델 구분 필요", 2,
                    serviceEvidence(FRAME_UUID, advertisedUuids, serviceDataKeys, "Brilliant Labs Frame / Halo 공통 서비스 식별자")));
        }
        boolean heycyanName = value.startsWith("heycyan");
        boolean heycyanUuid = advertisedUuids.contains(HEYCYAN_UUID) || serviceDataKeys.contains(HEYCYAN_UUID);
        if (!deleted.contains("HeyCyan OEM") && (heycyanName || heycyanUuid)) {
            List<Evidence> evidence = new ArrayList<>();
            if (heycyanName) evidence.add(nameEvidence(name, "기기 이름이 ‘HeyCyan’으로 시작"));
            if (heycyanUuid) evidence.addAll(serviceEvidence(HEYCYAN_UUID, advertisedUuids, serviceDataKeys,
                    "HeyCyan 계열 등록 서비스 식별자"));
            matches.add(new Match("HeyCyan 계열 의심 기기", heycyanName && heycyanUuid
                    ? "이름 및 BLE 서비스 UUID" : heycyanName ? "HeyCyan 이름" : "기존 APK의 BLE 서비스 UUID",
                    heycyanName && heycyanUuid ? 2 : 1, evidence));
        }
        return matches;
    }

    static List<Match> match(RadioScanner.Signal signal, SessionConfig config) {
        List<Match> matches = new ArrayList<>();
        String observedName = signal.source() == RadioScanner.Mode.BLE ? signal.advertisedName()
                : signal.source() == RadioScanner.Mode.CLASSIC && signal.nameOrigin() != RadioScanner.NameOrigin.CLASSIC_RESPONSE
                ? null : signal.name();
        if (signal.source() != RadioScanner.Mode.WIFI && config.categories().contains(DeviceCategory.GLASSES)) {
            for (Match legacy : classify(observedName, signal.companies(), signal.advertisedUuids(),
                    signal.serviceDataKeys(), config.rules().deletedBuiltins())) {
                boolean classified = legacy.evidence().stream().anyMatch(value -> !value.type().equals("제조사 정보 일치"));
                boolean alertAllowed = classified && (legacy.evidence().stream().anyMatch(value -> value.type().equals("이름 일치"))
                        || hasDistinctServiceIdentifiers(legacy.evidence()));
                String source = catalogSource(legacy);
                String reason = legacy.reason().contains("실기 검증 전") ? legacy.reason() : legacy.reason() + " · 실기 검증 전";
                matches.add(new Match(legacy.label(), reason, legacy.strength(), legacy.evidence(),
                        classified ? DeviceCategory.GLASSES : DeviceCategory.UNKNOWN, DetectionRule.EvidenceLevel.LEGACY,
                        "builtin/" + legacy.label(), source, alertAllowed, classified));
            }
        }
        if (signal.source() != RadioScanner.Mode.WIFI && observedName != null) {
            String normalized = normalize(observedName);
            for (DeviceCatalog.Entry entry : DeviceCatalog.LEGACY_ENTRIES) {
                if (entry.category() == DeviceCategory.GLASSES || !config.categories().contains(entry.category())
                        || config.rules().deletedBuiltins().contains(entry.label()) || !entry.matches(normalized)) continue;
                matches.add(new Match(entry.label() + " 의심 기기", entry.evidence(), 1,
                        List.of(nameEvidence(observedName, entry.label() + " 제품명 패턴 · 실기 검증 전")),
                        entry.category(), entry.evidenceLevel(), "builtin/" + entry.label(), entry.source(), true, true));
            }
        }
        if (!signal.previousResult()) {
            for (DeviceCatalog.Entry entry : DeviceCatalog.PUBLISHED_ENTRIES) {
                if (!entry.paths().contains(signal.source()) || !config.categories().contains(entry.category())
                        || config.rules().deletedBuiltins().contains(entry.label())) continue;
                var evidence = entry.signalRule().evidence(signal);
                if (evidence.isEmpty()) continue;
                matches.add(new Match(entry.label() + " 의심 기기", entry.evidence(),
                        evidence.size() > 1 ? 2 : 1, evidence, entry.category(), entry.evidenceLevel(),
                        "builtin/" + entry.label(), entry.source(), entry.evidenceLevel() != DetectionRule.EvidenceLevel.CLUE, true));
            }
        }
        for (DetectionRule rule : config.rules().rules()) {
            if (!config.categories().contains(rule.category()) || !rule.matches(signal)) continue;
            boolean classified = rule.classifying();
            String type = switch (rule.field()) {
                case ADVERTISED_UUID -> "사용자 등록 · 광고 서비스 UUID 일치";
                case SERVICE_DATA_KEY -> "사용자 등록 · 서비스 데이터 키 일치";
                case LEGACY_UUID -> "사용자 등록 · 기존 UUID 일치";
                case MANUFACTURER_ID -> "사용자 등록 · 제조사 정보 일치";
                default -> "사용자 등록 · 이름 일치";
            };
            String received = switch (rule.field()) {
                case BLE_ADVERTISED_NAME -> signal.advertisedName();
                case DEVICE_NAME -> signal.cachedName();
                case LEGACY_NAME, CLASSIC_NAME, SSID -> signal.name();
                default -> rule.value();
            };
            String criterion = rule.source().label + " · " + rule.field().label + " · 정확히 일치";
            if (rule.evidenceLevel() == DetectionRule.EvidenceLevel.LEGACY) criterion += " · 출처 미지정 · 기존 등록";
            List<Evidence> evidence = rule.field() == DetectionRule.Field.LEGACY_UUID
                    ? serviceEvidence(rule.value(), signal.advertisedUuids(), signal.serviceDataKeys(), criterion).stream()
                    .map(item -> new Evidence("사용자 등록 · " + item.type(), item.received(), item.criterion()))
                    .collect(java.util.stream.Collectors.toList())
                    : List.of(new Evidence(type, received, criterion));
            matches.add(new Match(rule.category().label + " · 사용자 등록", criterion, 3,
                    evidence, rule.category(), rule.evidenceLevel(),
                    rule.id(), rule.sourceUrl(), rule.preciseAlert() && classified, classified));
        }
        addClues(signal, config, matches);
        matches.sort(java.util.Comparator.comparingInt(Match::strength).reversed());
        return List.copyOf(matches);
    }

    private static void addClues(RadioScanner.Signal signal, SessionConfig config, List<Match> matches) {
        if (signal.source() == RadioScanner.Mode.BLE && signal.serviceDataKeys().contains("0000fe2c-0000-1000-8000-00805f9b34fb")
                && config.categories().contains(DeviceCategory.AUDIO)) {
            matches.add(new Match("Fast Pair 계열 단서", "공용 서비스 식별자 · 유형·모델 미확인", 0,
                    List.of(new Evidence("서비스 데이터 키 일치", "0000fe2c-0000-1000-8000-00805f9b34fb", "Fast Pair 서비스 데이터 키")),
                    DeviceCategory.UNKNOWN, DetectionRule.EvidenceLevel.CLUE, "clue/fastpair",
                    "https://developers.google.com/nearby/fast-pair/specifications/service/provider", false, false));
        }
        if (signal.source() == RadioScanner.Mode.WIFI && signal.name() != null && signal.name().startsWith("Tapo_Cam_")
                && config.categories().contains(DeviceCategory.CAMERA)) {
            matches.add(new Match("카메라 설정망 단서", "SSID 접두사 · 실물 모델·상태 확인 필요", 0,
                    List.of(new Evidence("SSID 보조 단서", signal.name(), "Tapo 설정망 접두사")), DeviceCategory.UNKNOWN,
                    DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT, "clue/tapo-setup",
                    "https://www.tp-link.com/us/support/faq/2710/", false, false));
        }
        if (signal.appearance() != null) {
            matches.add(new Match("BLE 장비 유형 단서", "광고 Appearance · 모델·기능 확인 필요", 0,
                    List.of(new Evidence("장비 유형 보조 단서", String.format(Locale.ROOT, "0x%04X", signal.appearance()),
                            "BLE Appearance 스캔 값")), DeviceCategory.UNKNOWN, DetectionRule.EvidenceLevel.CLUE,
                    "clue/appearance", "https://www.bluetooth.com/specifications/assigned-numbers/", false, false));
        }
        if (signal.bluetoothClass() != null) {
            matches.add(new Match("Bluetooth 장비군 단서", "Classic 장비 클래스 · 모델·기능 확인 필요", 0,
                    List.of(new Evidence("장비 유형 보조 단서", String.format(Locale.ROOT, "0x%04X", signal.bluetoothClass()),
                            "Bluetooth Classic 장비 클래스")), DeviceCategory.UNKNOWN, DetectionRule.EvidenceLevel.CLUE,
                    "clue/classic-class", "https://developer.android.com/reference/android/bluetooth/BluetoothClass.Device", false, false));
        }
    }

    static boolean conflict(List<Match> matches) {
        return matches.stream().filter(Match::classified).map(Match::category)
                .filter(category -> category != DeviceCategory.CUSTOM && category != DeviceCategory.UNKNOWN).distinct().count() > 1;
    }

    private static String catalogSource(Match match) {
        String label = match.label();
        String catalogLabel = label.startsWith("Rokid 계열") ? "Rokid CXR 통신 계열"
                : label.startsWith("Ray-Ban 계열") || label.startsWith("Meta/Luxottica") ? "Ray-Ban / Meta"
                : label.startsWith("HeyCyan 계열") ? "HeyCyan OEM" : label;
        return DeviceCatalog.ENTRIES.stream()
                .filter(entry -> catalogLabel.startsWith(entry.label()))
                .map(DeviceCatalog.Entry::source).findFirst().orElse("");
    }

    static boolean alertEligible(List<Match> matches) {
        return !conflict(matches) && matches.stream().anyMatch(match -> match.classified() && match.alertAllowed());
    }

    static String[] bleServiceFilters(SessionConfig config) {
        Set<String> values = new java.util.LinkedHashSet<>();
        if (config.categories().contains(DeviceCategory.GLASSES)) {
            if (!config.rules().deletedBuiltins().contains("Rokid CXR 통신 계열")) values.add(ROKID_DISCOVERY_UUID);
            if (!config.rules().deletedBuiltins().contains("Brilliant Labs Frame")) values.add(FRAME_UUID);
            if (!config.rules().deletedBuiltins().contains("Even Realities G2")) values.add(G2_UUID);
            if (!config.rules().deletedBuiltins().contains("HeyCyan OEM")) values.add(HEYCYAN_UUID);
        }
        for (DetectionRule rule : config.rules().rules()) {
            if (!config.categories().contains(rule.category()) || !rule.source().accepts(RadioScanner.Mode.BLE)) continue;
            if (rule.field() == DetectionRule.Field.ADVERTISED_UUID || rule.field() == DetectionRule.Field.LEGACY_UUID)
                values.add(rule.value());
        }
        return values.toArray(String[]::new);
    }

    static int[] bleCompanyFilters(SessionConfig config) {
        Set<Integer> values = new java.util.LinkedHashSet<>();
        if (config.categories().contains(DeviceCategory.GLASSES)) for (int id : bleCompanyFilters()) values.add(id);
        for (DetectionRule rule : config.rules().rules()) {
            if (config.categories().contains(rule.category()) && rule.field() == DetectionRule.Field.MANUFACTURER_ID
                    && rule.source().accepts(RadioScanner.Mode.BLE)) values.add(Integer.parseInt(rule.value().substring(2), 16));
        }
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    static String[] bleNameFilters(SessionConfig config) {
        return config.rules().rules().stream().filter(rule -> config.categories().contains(rule.category())
                && rule.source() == DetectionRule.Source.BLE && rule.field() == DetectionRule.Field.BLE_ADVERTISED_NAME
                && !rule.ignoreCase()).map(DetectionRule::value).distinct().toArray(String[]::new);
    }

    private static Evidence nameEvidence(String name, String criterion) {
        return new Evidence("이름 일치", name, criterion);
    }

    private static Evidence uuidEvidence(String uuid, String criterion) {
        return new Evidence("광고 서비스 UUID 일치", uuid, criterion);
    }

    private static boolean hasDistinctServiceIdentifiers(List<Evidence> evidence) {
        // One UUID can appear in both BLE fields without providing independent corroboration.
        return evidence.stream()
                .filter(item -> item.type().equals("광고 서비스 UUID 일치") || item.type().equals("서비스 데이터 키 일치"))
                .map(item -> item.received().toLowerCase(Locale.ROOT))
                .distinct().limit(2).count() == 2;
    }

    private static List<Evidence> serviceEvidence(String uuid, Set<String> advertisedUuids, Set<String> serviceDataKeys,
                                                 String criterion) {
        List<Evidence> evidence = new ArrayList<>();
        if (advertisedUuids.contains(uuid)) evidence.add(uuidEvidence(uuid, criterion + " · BLE 광고 서비스 UUID"));
        if (serviceDataKeys.contains(uuid)) evidence.add(new Evidence("서비스 데이터 키 일치", uuid,
                criterion + " · BLE 서비스 데이터 키"));
        return evidence;
    }

    private static Evidence companyEvidence(Set<Integer> received, Set<Integer> expected, String criterion) {
        String values = received.stream().filter(expected::contains).sorted()
                .map(id -> String.format(Locale.ROOT, "0x%04X", id)).collect(java.util.stream.Collectors.joining(", "));
        return new Evidence("제조사 정보 일치", values, criterion);
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
