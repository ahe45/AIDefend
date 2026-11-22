package com.uplusys.deviceguard;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Built-in research catalog. Product-name hypotheses remain weak matches until observed. */
final class DeviceCatalog {
    static final String FRAME_UUID = "7a230001-5475-a6a4-654c-8431f6ad49c4";
    static final String G1_UART_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
    static final String G2_UUID = "00002760-08c2-11e1-9073-0e8ac72e0000";
    static final String HEYCYAN_UUID = "7905fff0-b5ce-4e99-a40f-4b1e122d00d0";
    static final String ROKID_DISCOVERY_UUID = "00009100-0000-1000-8000-00805f9b34fb";
    static final String ROKID_SERVICE_DATA_KEY = "00009a01-0000-1000-8000-00805f9b34fb";

    interface SignalRule {
        List<DeviceMatcher.Evidence> evidence(RadioScanner.Signal signal);
    }

    record Entry(String label, String models, Pattern name, String evidence, String source,
                 DeviceCategory category, DetectionRule.EvidenceLevel evidenceLevel,
                 Set<RadioScanner.Mode> paths, SignalRule signalRule) {
        Entry(String label, String models, String regex, String evidence, String source) {
            this(label, models, regex, evidence, source, DeviceCategory.GLASSES, DetectionRule.EvidenceLevel.LEGACY);
        }
        Entry(String label, String models, String regex, String evidence, String source,
              DeviceCategory category, DetectionRule.EvidenceLevel level) {
            this(label, models, regex.isEmpty() ? null : Pattern.compile(regex), evidence, source, category, level,
                    Set.of(), null);
        }

        boolean matches(String normalizedName) {
            return name != null && name.matcher(normalizedName).matches();
        }
    }

    static Entry published(String label, String models, DeviceCategory category, DetectionRule.EvidenceLevel level,
                           String evidence, String source, SignalRule rule) {
        return new Entry(label, models, null, evidence, source, category, level, Set.of(RadioScanner.Mode.BLE), rule);
    }

    static Entry bluetoothName(String label, String models, String name, DeviceCategory category,
                               String evidence, String source) {
        Pattern namePattern = Pattern.compile(name, Pattern.LITERAL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return new Entry(label, models, namePattern, evidence, source, category, DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT,
                Set.of(RadioScanner.Mode.BLE, RadioScanner.Mode.CLASSIC), signal -> {
                    boolean ble = signal.source() == RadioScanner.Mode.BLE;
                    String observed = ble ? signal.advertisedName()
                            : signal.nameOrigin() == RadioScanner.NameOrigin.CLASSIC_RESPONSE ? signal.name() : null;
                    if (!name.equalsIgnoreCase(observed)) return List.of();
                    String field = ble ? DetectionRule.Field.BLE_ADVERTISED_NAME.label : DetectionRule.Field.CLASSIC_NAME.label;
                    return List.of(new DeviceMatcher.Evidence(field, observed, "문서에 명시된 검색명과 전체 일치"));
                });
    }

    static Entry bleName(String label, String models, String regex, String evidence, String source,
                         DeviceCategory category, DetectionRule.EvidenceLevel level) {
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return new Entry(label, models, pattern, evidence, source, category, level, Set.of(RadioScanner.Mode.BLE), signal -> {
            String observed = signal.advertisedName();
            if (observed == null || !pattern.matcher(observed).matches()) return List.of();
            return List.of(new DeviceMatcher.Evidence(DetectionRule.Field.BLE_ADVERTISED_NAME.label,
                    observed, label + " 등록 광고 이름 형식과 일치"));
        });
    }

    static Entry ssid(String label, String models, String regex, String evidence, String source) {
        Pattern pattern = Pattern.compile(regex);
        return new Entry(label, models, pattern, evidence, source, DeviceCategory.CAMERA,
                DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT, Set.of(RadioScanner.Mode.WIFI), signal -> {
                    String observed = signal.name();
                    if (observed == null || !pattern.matcher(observed).matches()) return List.of();
                    return List.of(new DeviceMatcher.Evidence(DetectionRule.Field.SSID.label, observed,
                            label + " 등록 SSID 형식과 일치"));
                });
    }

    static final List<Entry> LEGACY_ENTRIES = List.of(
            new Entry("Even Realities G1", "G1", "^g1_[0-9]+_[lr]_[a-z0-9]+$",
                    "공식 SDK의 좌우 이름 형식 · 실기 검증 전\n보조 UART UUID " + G1_UART_UUID,
                    "https://github.com/even-realities/EvenDemoApp"),
            new Entry("Even Realities G2", "G2", "^even[ _-]+g2(?:$|[ _-].*)",
                    "공개 역공학의 이름 형식 · 실기 검증 전\n서비스 UUID " + G2_UUID,
                    "https://github.com/i-soxi/even-g2-protocol/blob/main/docs/ble-uuids.md"),
            new Entry("Brilliant Labs Frame", "Frame", "^brilliant[ _-]+(?:labs[ _-]+)?frame(?:$|[ _-].*)",
                    "제품명 기반 이름 · 공식 SDK 광고 UUID · 실기 검증 전\n서비스 UUID " + FRAME_UUID,
                    "https://github.com/brilliantlabsAR/frame-sdk-python"),
            new Entry("Brilliant Labs Halo", "Halo", "^brilliant[ _-]+(?:labs[ _-]+)?halo(?:$|[ _-].*)",
                    "제품명 기반 · 광고 이름과 UUID 실기 검증 전",
                    "https://github.com/brilliantlabsAR/brilliant_sdk"),
            new Entry("Rokid Glass3 기업용", "Glass 3", "^(?:rokid[ _-]+)?glass3_[a-z0-9]+$",
                    "공식 검색 이름 형식 · 실기 검증 전",
                    "https://tatooine.rokidcdn.com/prod/ebg-sdk/releases/dev/manuals/lingmou_manual.pdf"),
            new Entry("Rokid Glasses Style", "AI Glasses Style", "^rokid[ _-]+(?:ai[ _-]+)?glasses[ _-]+style(?:$|[ _-].*)",
                    "제품명 기반 · 광고 이름 실기 검증 전",
                    "https://www.rokid.com/en-US"),
            new Entry("Rokid Glasses", "Glasses", "^rokid[ _-]+glasses(?:$|[ _-].*)",
                    "페어링 표시 이름 · BLE 광고 실기 검증 전",
                    "https://global.rokid.com/pages/faq"),
            new Entry("Rokid CXR 통신 계열", "적용 모델과 펌웨어 실기 검증 전", "",
                    "공개 역공학의 광고 서비스 UUID " + ROKID_DISCOVERY_UUID
                            + "\n서비스 데이터 UUID " + ROKID_SERVICE_DATA_KEY,
                    "https://github.com/buildwithfenna/rokid-docs/blob/main/yodaos/docs/apps/cxr-service.md"),
            new Entry("Solos AirGo A5", "AirGo A5", "^solos[ _-]+airgo[ _-]*a5(?:$|[ _-].*)",
                    "공식 페어링 이름 · 실기 검증 전",
                    "https://solosglasses.com/pages/general-frequently-asked-questions"),
            new Entry("Solos AirGo V2", "AirGo V2", "^solos[ _-]+airgo[ _-]*v2(?:$|[ _-].*)",
                    "제품명 기반 · 광고 이름 실기 검증 전",
                    "https://solosglasses.com/pages/user-manual-1"),
            new Entry("Solos AirGo 3 / AirGo V", "AirGo 3, AirGo V", "^solos[ _-]+airgo[ _-]*3(?:$|[ _-].*)",
                    "공식 오디오 페어링 이름 · 카메라 유무 미확인",
                    "https://solosglasses.com/pages/general-frequently-asked-questions"),
            new Entry("Solos AirGo V 카메라", "AirGo V", "^solos[ _-]+vision(?:$|[ _-].*)",
                    "공식 카메라 페어링 이름 · 실기 검증 전",
                    "https://solosglasses.com/pages/general-frequently-asked-questions"),
            new Entry("Solos AirGo", "AirGo", "^solos[ _-]+airgo(?:$|[ _-].*)",
                    "공식 페어링 이름 · 세부 모델 미확인",
                    "https://solosglasses.com/pages/user-manual-1"),
            new Entry("Xiaomi Smart Audio Glasses", "Smart Audio Glasses, Mijia Smart Audio Glasses",
                    "^(?:xiaomi|mijia)[ _-]+smart[ _-]+audio[ _-]+glasses(?:$|[ _-].*)",
                    "Xiaomi 공식 연결 이름 / Mijia 제품명 · 오디오형 · 실기 검증 전",
                    "https://www.mi.com/global/support/faq/details/KA-513031/"),
            new Entry("Xiaomi AI Glasses", "AI Glasses", "^(?:xiaomi|mijia)[ _-]+ai[ _-]+glasses(?:$|[ _-].*)",
                    "제품명 기반 · 광고 이름과 UUID 실기 검증 전",
                    "https://www.mi.com/prod/xiaomi-ai-glasses"),
            new Entry("Razer Anzu", "Anzu", "^razer[ _-]+anzu(?:$|[ _-].*)",
                    "공식 페어링 안내 · 오디오형 · 실기 검증 전",
                    "https://dl.razerzone.com/master-guides/Anzu/Razer%20Anzu%20-%20Master%20Guide%20-%20en.pdf"),
            new Entry("Bose Frames", "Alto, Rondo, Tempo, Tenor, Soprano", "^bose[ _-]+frames(?:$|[ _-].*)",
                    "공식 기본 이름 · 사용자 변경 가능 · 오디오형",
                    "https://assets.bosecreative.com/m/10026acaded9bbf4/original/856770_OG_TENOR-FRAMES_en.pdf"),
            new Entry("Lucyd", "Lyte, Armor, AERO", "^lucyd(?:$|[ _-].*)",
                    "공식 연결 안내 · 오디오형 · 모델별 이름 실기 검증 전",
                    "https://help.lucyd.co/app-and-bluetooth/"),
            new Entry("Reebok 스마트안경", "Optical, Sport Voltage, Octane, Flash, Nitrous, Octane Shift, Nitrous Shift",
                    "^reebok[ _-]+(?:optical|sport|voltage|octane|flash|nitrous)(?:$|[ _-].*)",
                    "제품명 기반 · 오디오형 · 광고 이름 실기 검증 전",
                    "https://help.lucyd.co/app-and-bluetooth/"),
            new Entry("HUAWEI Eyewear", "Eyewear, Eyewear 2", "^huawei[ _-]+eyewear(?:$|[ _-].*)",
                    "제품명 기반 · 오디오형 · 실기 검증 전",
                    "https://consumer.huawei.com/en/support/content/en-us15675561/"),
            new Entry("Vuzix", "Z100, Blade, Blade 2, Shield, M400, M4000, LX1",
                    "^vuzix[ _-]+(?:z100|blade|shield|m4000?|lx1)(?:$|[ _-].*)",
                    "제품명 기반 · 모델별 광고 이름 실기 검증 전\n보조 제조사 ID 0x060C",
                    "https://support.vuzix.com/"),
            new Entry("RayNeo", "X2, X3 Pro, iO", "^rayneo[ _-]+(?:x2|x3[ _-]*pro|io)(?:$|[ _-].*)",
                    "제품명 기반 · 무선 연결 지원 · 광고 이름 실기 검증 전",
                    "https://www.rayneo.com/blogs/news/rayneo-bluetooth-versions-explained"),
            new Entry("Amazon Echo Frames", "Echo Frames 2세대, 3세대", "^(?:amazon[ _-]+)?echo[ _-]+frames(?:$|[ _-].*)",
                    "제품명 기반 · 오디오형 · 실기 검증 전",
                    "https://digprjsurvey.amazon.com/csad/help/node/GHRYQ6GHE4A5TUD2"),
            new Entry("Soundcore Frames", "Frames", "^(?:anker[ _-]+)?soundcore[ _-]+frames(?:$|[ _-].*)",
                    "제품명 기반 · 오디오형 · 실기 검증 전", "https://www.soundcore.com/"),
            new Entry("HTC VIVE Eagle", "VIVE Eagle", "^(?:htc[ _-]+)?vive[ _-]+eagle(?:$|[ _-].*)",
                    "공식 연결 안내 · 광고 식별자 실기 검증 전",
                    "https://www.vive.com/hk/support/vive-eagle-en/category_howto/pairing-and-unpairing-your-glasses.html"),
            new Entry("Halliday", "DigiWindow, G2", "^halliday(?:$|[ _-].*)",
                    "제품명 기반 · 광고 이름과 UUID 실기 검증 전",
                    "https://support.hallidayglobal.com/hc/en-us/articles/60154389484825-General-FAQs"),
            new Entry("INMO", "AIR, AIR2, GO3", "^inmo[ _-]+(?:air[ _-]*[123]?|go[ _-]*[123]?)(?:$|[ _-].*)",
                    "제품명 기반 · 광고 이름과 UUID 실기 검증 전", "https://www.inmolens.com/download"),
            new Entry("Quark", "S1, G1", "^quark[ _-]+[sg]1(?:$|[ _-].*)",
                    "제품명 기반 · 광고 이름과 UUID 실기 검증 전",
                    "https://community.alibabacloud.com/tags/type_blog-tagid_38955/"),
            new Entry("Mentra", "Live, Mach1", "^mentra[ _-]+(?:live|mach[ _-]*1)(?:$|[ _-].*)",
                    "공개 SDK 지원 모델 · 광고 식별자 실기 검증 전",
                    "https://github.com/Mentra-Community/MentraOS/blob/dev/glasses-compatibility.md"),
            new Entry("RealWear", "Navigator 500, 520, Z1", "^(?:realwear[ _-]+)(?:navigator[ _-]+)?(?:500|520|z1)(?:$|[ _-].*)",
                    "산업용 제품명 기반 · 광고 식별자 실기 검증 전",
                    "https://support.realwear.com/knowledge/realwear-navigator-series"),
            new Entry("HeyCyan OEM", "HeyCyan, W610, W630, Nilox Smart AI Glasses",
                    "^(?:heycyan(?:$|[ _-].*)|w6[13]0_[a-z0-9]+)$",
                    "공개 역공학 기반 OEM 의심 기기 · 판매 모델 미확인\n서비스 UUID " + HEYCYAN_UUID,
                    "https://github.com/ebowwa/HeyCyanSmartGlassesSDK"),
            new Entry("Glasses 이름 스마트글래스", "이름에 Glasses가 포함된 Bluetooth 기기", ".*glasses.*",
                    "Bluetooth 이름에 Glasses 포함 · 제조사와 모델 미확인",
                    "사용자 요청 · 스캔 예시 Glasses_4097"),
            new Entry("Ray-Ban / Meta", "Stories, Meta 세대별 제품, Meta Ray-Ban Display", "",
                    "이름과 제조사 ID 0x01AB / 0x058E / 0x0D53 복합 판별 · 세대 식별 실기 검증 전",
                    "https://www.ray-ban.com/global/c/frequently-asked-questions-ray-ban-meta-smart-glasses"),
            new Entry("Oakley Meta", "HSTN, Vanguard", "",
                    "이름과 제조사 ID 0x01AB / 0x058E / 0x0D53 복합 판별 · 세대 식별 실기 검증 전", "https://www.oakley.com/"),
            new Entry("Snap Spectacles", "Spectacles 시리즈, Spectacles ’24", "",
                    "이름과 Snap 제조사 ID 0x03C2 판별 · 사용자 이름 변경 가능",
                    "https://support.spectacles.com/hc/en-us/articles/360033393052-Setting-up-and-pairing-your-Spectacles"),
            new Entry("XREAL 유선형", "Air, One 계열", "", "무선 식별자 확보 전 · 카탈로그 등록",
                    "https://resource.xreal.com/www-xreal-com/docs/manual/XREAL_One_User_Manuals-250407.pdf"),
            new Entry("Rokid 유선형", "Max, Max 2", "", "안경과 Station의 무선 기능 구분 필요 · 카탈로그 등록",
                    "https://global.rokid.com/collections/rokid-ar-glasses-series"),
            new Entry("RayNeo 유선형", "Air, GT 계열", "", "무선 식별자 확보 전 · 카탈로그 등록",
                    "https://www.rayneo.com/blogs/news/rayneo-bluetooth-versions-explained"),
            new Entry("VITURE 유선형", "Luma 계열", "", "무선 식별자 확보 전 · 카탈로그 등록",
                    "https://www.viture.com/en-US/luma"),
            new Entry("Epson Moverio", "BT-40, BT-45 계열", "", "안경과 컨트롤러의 무선 기능 구분 필요 · 카탈로그 등록",
                    "https://tech.moverio.epson.com/en/technical_faq/"),
            new Entry("Insta360 GO 3S", "GO 3S, Action Pod", "",
                    "공식 무선 사양 · 실제 SSID·BLE 조건 등록 필요 · 실기 검증 전",
                    "https://onlinemanual.insta360.com/go3s/en-us/faq/specs/wifi", DeviceCategory.CAMERA,
                    DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT),
            new Entry("DJI Osmo Nano", "카메라, Vision Dock", "",
                    "공식 무선 사양 · 실제 SSID·BLE 조건 등록 필요 · 실기 검증 전",
                    "https://www.dji.com/nano/specs", DeviceCategory.CAMERA, DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT),
            new Entry("Apple AirPods", "AirPods 계열", "^(?:.*[ _-])?airpods(?:$|[ _-].*)",
                    "제품 이름 단서 · 케이스·페어링·연결 상태별 실기 검증 전",
                    "https://support.apple.com/guide/airpods/pair-airpods-with-a-non-apple-device-dev499c9718b/web",
                    DeviceCategory.AUDIO, DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT),
            new Entry("Samsung Galaxy Buds", "Galaxy Buds3, Buds3 Pro", "^galaxy[ _-]+buds(?:.*)$",
                    "제품 이름 단서 · 모델·케이스 상태별 실기 검증 전",
                    "https://www.samsung.com/us/support/troubleshoot/TSG10013267/", DeviceCategory.AUDIO,
                    DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT),
            new Entry("Sony WF-1000XM5", "WF-1000XM5", "^(?:le[ _-]+)?(?:sony[ _-]+)?wf[ _-]?1000xm5(?:$|[ _-].*)",
                    "제품 이름 단서 · Classic·LE Audio 상태별 실기 검증 전",
                    "https://www.sony.com/electronics/support/wireless-headphones-bluetooth-headphones/wf-1000xm5/articles/00366813",
                    DeviceCategory.AUDIO, DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT),
            new Entry("Apple Watch", "Apple Watch 계열", "^apple[ _-]+watch(?:$|[ _-].*)",
                    "제품 이름 단서 · 세대·연결 상태별 실기 검증 전", "https://support.apple.com/en-la/109319",
                    DeviceCategory.WATCH, DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT),
            new Entry("Samsung Galaxy Watch", "Galaxy Watch7 등", "^galaxy[ _-]+watch(?:.*)$",
                    "제품 이름 단서 · Bluetooth·LTE 모델별 실기 검증 전",
                    "https://www.samsung.com/sec/watches/galaxy-watch/galaxy-watch7/specs/", DeviceCategory.WATCH,
                    DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT),
            new Entry("Google Pixel Watch", "Pixel Watch 계열", "^(?:google[ _-]+)?pixel[ _-]+watch(?:$|[ _-].*)",
                    "제품 이름 단서 · 세대·변형별 실기 검증 전",
                    "https://support.google.com/googlepixelwatch/answer/12651869?hl=en", DeviceCategory.WATCH,
                    DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT),
            new Entry("Xiaomi Smart Band", "Smart Band 9", "^(?:xiaomi[ _-]+|mi[ _-]+)?smart[ _-]+band[ _-]+9(?:$|[ _-].*)",
                    "제품 이름 단서 · 운동·대기·연결 상태별 실기 검증 전",
                    "https://www.mi.com/global/product/xiaomi-smart-band-9/specs/", DeviceCategory.WATCH,
                    DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT),
            new Entry("Fitbit Charge 6", "Charge 6", "^fitbit[ _-]+charge[ _-]+6(?:$|[ _-].*)",
                    "제품 이름 단서 · 운동·대기·연결 상태별 실기 검증 전",
                    "https://store.google.com/product/fitbit_charge_6_specs?hl=en-US", DeviceCategory.WATCH,
                    DetectionRule.EvidenceLevel.OFFICIAL_PRODUCT)
    );

    static final List<Entry> PUBLISHED_ENTRIES;
    static final List<Entry> ENTRIES;

    static {
        var published = new ArrayList<Entry>();
        published.addAll(DocumentedGlasses.entries());
        published.addAll(DocumentedCameras.entries());
        published.addAll(DocumentedWearables.entries());
        PUBLISHED_ENTRIES = List.copyOf(published);
        var entries = new ArrayList<>(LEGACY_ENTRIES);
        entries.addAll(PUBLISHED_ENTRIES);
        ENTRIES = List.copyOf(entries);
    }

    private DeviceCatalog() {}
}
