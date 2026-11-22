package com.uplusys.deviceguard;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.SparseArray;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RadioScanner {
    enum Mode {
        BLE("BLE"), CLASSIC("Classic Bluetooth"), COMBINED("BLE + Classic"), WIFI("Wi-Fi AP"),
        ALL("통합 스캔 · BLE + Classic + Wi-Fi AP");
        final String label;
        Mode(String label) { this.label = label; }
    }

    enum NameOrigin {
        ADVERTISEMENT("BLE 광고 이름"), CACHE("기기 이름 캐시"), CLASSIC_RESPONSE("Classic 응답 이름"),
        WIFI_SSID("Wi-Fi SSID"), UNKNOWN("출처 미확인");
        final String label;
        NameOrigin(String label) { this.label = label; }
    }

    record Signal(Mode source, String address, String name, Integer rssi,
                  Set<Integer> companies, Set<String> advertisedUuids, Set<String> serviceDataKeys,
                  Map<Integer, String> manufacturerData, Map<String, String> serviceData,
                  String advertisedName, String cachedName, NameOrigin nameOrigin, long observedAtElapsed,
                  Integer bluetoothClass, Integer wifiFrequency, Integer appearance, boolean previousResult) {
        Signal {
            companies = Set.copyOf(companies);
            advertisedUuids = Set.copyOf(advertisedUuids);
            serviceDataKeys = Set.copyOf(serviceDataKeys);
            manufacturerData = Map.copyOf(manufacturerData);
            serviceData = Map.copyOf(serviceData);
        }

        Signal(Mode source, String address, String name, Integer rssi, Set<Integer> companies,
               Set<String> advertisedUuids, Set<String> serviceDataKeys,
               Map<Integer, String> manufacturerData, Map<String, String> serviceData) {
            this(source, address, name, rssi, companies, advertisedUuids, serviceDataKeys, manufacturerData, serviceData,
                    source == Mode.BLE ? name : null, null,
                    source == Mode.BLE ? NameOrigin.ADVERTISEMENT : source == Mode.WIFI ? NameOrigin.WIFI_SSID
                            : NameOrigin.CLASSIC_RESPONSE, 0, null, null, null, false);
        }

        Signal(Mode source, String address, String name, Integer rssi, Set<Integer> companies,
               Set<String> advertisedUuids, Set<String> serviceDataKeys) {
            this(source, address, name, rssi, companies, advertisedUuids, serviceDataKeys, Map.of(), Map.of());
        }
    }

    enum Outcome { COMPLETED, STOPPED, FAILED, INTERRUPTED }
    enum PathState {
        NOT_RUN("검색 미실행"), RUNNING("검색 중"), COMPLETE("완료"), PERMISSION_REQUIRED("권한 필요"),
        THROTTLED("요청 제한 또는 실패"), FAILED("실패"), STALE("이전 결과"), STOPPED("중지");
        final String label;
        PathState(String label) { this.label = label; }
    }
    record PathStatus(PathState state, String message, long updatedAtElapsed) {}

    interface Listener {
        void status(Mode phase, String text);
        void found(Signal signal);
        void ended(Outcome outcome, String message);
        default void routesChanged(Map<Mode, PathStatus> states) {}
        default boolean beforeRoute(Mode route) { return true; }
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EnumMap<Mode, PathStatus> states = new EnumMap<>(Mode.class);
    private Mode mode;
    private Mode phase;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private ScanCallback filteredCallback;
    private BroadcastReceiver receiver;
    private boolean ownsDiscovery;
    private boolean primaryFailed;
    private boolean filteredFailed;
    private int generation;
    private int routeIndex;
    private int cycles;
    private long cycleStarted;
    private long sessionStarted;
    private long routeStarted;
    private long wifiRequestedAt;
    private long lastWifiAttempt;
    private List<Mode> routes = List.of();
    private boolean repeat;
    private boolean wifiAttempted;
    private long singleBleDuration;
    private SavedTargets.Snapshot activeTargets;
    private SessionConfig config;

    RadioScanner(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean running() { return mode != null; }
    Map<Mode, PathStatus> routeStates() { return Collections.unmodifiableMap(new EnumMap<>(states)); }

    void start(SessionConfig config, long duration) {
        if (running()) return;
        this.config = config;
        List<Mode> plan = orderedPaths(config.paths(), config.automatic(),
                config.categories().contains(DeviceCategory.CAMERA), foreground());
        begin(config.mode(), plan, duration, config.rules(), config.automatic() && plan.size() > 1);
    }

    static List<Mode> orderedPaths(List<Mode> paths, boolean automatic, boolean camera, boolean foreground) {
        List<Mode> plan = new ArrayList<>(paths);
        if (automatic && camera && plan.remove(Mode.WIFI)) {
            plan.add(foreground ? 0 : plan.size(), Mode.WIFI);
        }
        return List.copyOf(plan);
    }

    void start(Mode requested, long duration, SavedTargets.Snapshot targets) {
        if (running()) return;
        config = null;
        List<Mode> plan = switch (requested) {
            case BLE -> List.of(Mode.BLE);
            case CLASSIC -> List.of(Mode.CLASSIC);
            case WIFI -> List.of(Mode.WIFI);
            case COMBINED -> List.of(Mode.BLE, Mode.CLASSIC);
            case ALL -> List.of(Mode.BLE, Mode.CLASSIC, Mode.WIFI);
        };
        begin(requested, plan, duration, targets, false);
    }

    private void begin(Mode requested, List<Mode> plan, long duration, SavedTargets.Snapshot targets, boolean automaticRepeat) {
        if (plan.isEmpty()) {
            listener.ended(Outcome.FAILED, "실행할 검색 경로가 없습니다.");
            return;
        }
        mode = requested;
        routes = List.copyOf(plan);
        activeTargets = targets;
        routeIndex = 0;
        cycles = 0;
        wifiAttempted = false;
        repeat = automaticRepeat || requested == Mode.ALL;
        singleBleDuration = duration;
        sessionStarted = SystemClock.elapsedRealtime();
        cycleStarted = sessionStarted;
        states.clear();
        for (Mode route : List.of(Mode.BLE, Mode.CLASSIC, Mode.WIFI)) {
            states.put(route, new PathStatus(PathState.NOT_RUN, routes.contains(route) ? "검색 대기" : "선택한 경로에 포함되지 않음", sessionStarted));
        }
        listener.routesChanged(routeStates());
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        if (duration > 0 && (repeat || routes.size() == 1 && routes.get(0) == Mode.BLE)) {
            long sessionToken = sessionStarted;
            handler.postDelayed(() -> {
                if (running() && sessionStarted == sessionToken) stop(Outcome.COMPLETED, "설정한 스캔 시간 종료");
            }, duration);
        }
        startNext();
    }

    private void startNext() {
        if (!running()) return;
        if (routeIndex >= routes.size()) {
            if (!repeat) {
                boolean completed = states.values().stream().anyMatch(value -> value.state() == PathState.COMPLETE);
                boolean incomplete = routes.stream().anyMatch(route -> states.get(route).state() != PathState.COMPLETE);
                stop(completed ? Outcome.COMPLETED : Outcome.FAILED, completed
                        ? incomplete ? "부분 완료 · 경로별 상태 확인" : "선택한 경로 스캔 완료" : "검색 미완료 · 경로별 상태 확인");
                return;
            }
            routeIndex = 0;
            cycles++;
            long delay = Math.max(0, 30_000 - (SystemClock.elapsedRealtime() - cycleStarted));
            long token = sessionStarted;
            handler.postDelayed(() -> {
                if (running() && sessionStarted == token) {
                    cycleStarted = SystemClock.elapsedRealtime();
                    startNext();
                }
            }, delay);
            return;
        }
        Mode next = routes.get(routeIndex++);
        if (next == Mode.WIFI && config != null && config.automatic() && wifiAttempted) {
            // Automatic sessions keep the first Wi-Fi result/status while Bluetooth routes continue.
            handler.post(this::startNext);
            return;
        }
        phase = next;
        int token = ++generation;
        if (!ScanPolicy.missingPermissions(context, phase).isEmpty()) {
            finishPath(PathState.PERMISSION_REQUIRED, phase.label + " 권한 필요");
            return;
        }
        try {
            ScanPolicy.Problem problem = ScanPolicy.radioProblem(context, phase);
            if (problem != null) {
                finishPath(PathState.NOT_RUN, phase.label + " 검색 미실행 · " + problem.message());
                return;
            }
            if (!listener.beforeRoute(phase)) {
                finishPath(PathState.PERMISSION_REQUIRED, phase.label + " 실행에 필요한 서비스 권한 확인 필요");
                return;
            }
            if (!running()) return;
            routeStarted = SystemClock.elapsedRealtime();
            setPath(phase, PathState.RUNNING, phase.label + " 검색 중");
            switch (phase) {
                case BLE -> startBle(token);
                case CLASSIC -> startClassic(token);
                case WIFI -> startWifi(token);
                default -> finishPath(PathState.FAILED, "지원하지 않는 검색 경로");
            }
        } catch (SecurityException permissionChanged) {
            finishPath(PathState.PERMISSION_REQUIRED, phase.label + " 권한이 변경되었습니다.");
        } catch (IllegalStateException | IllegalArgumentException unavailable) {
            finishPath(PathState.FAILED, phase.label + " 무선 기능 또는 검색 필터를 사용할 수 없습니다.");
        }
    }

    private void setPath(Mode route, PathState state, String message) {
        states.put(route, new PathStatus(state, message, SystemClock.elapsedRealtime()));
        listener.routesChanged(routeStates());
        listener.status(route, message);
    }

    private void finishPath(PathState state, String message) {
        if (!running()) return;
        ++generation;
        stopBle();
        releaseDiscovery();
        setPath(phase, state, message);
        handler.post(this::startNext);
    }

    // startNext verifies this route's permissions and handles runtime permission revocation.
    @SuppressLint("MissingPermission")
    private void startBle(int token) {
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) {
            finishPath(PathState.FAILED, "BLE 스캐너를 사용할 수 없습니다.");
            return;
        }
        primaryFailed = false;
        List<ScanFilter> filters = bleFilters();
        filteredFailed = filters.isEmpty();
        callback = bleCallback(token, false);
        ScanSettings.Builder settings = new ScanSettings.Builder().setScanMode(
                repeat || singleBleDuration > 0 ? ScanSettings.SCAN_MODE_LOW_LATENCY : ScanSettings.SCAN_MODE_BALANCED);
        if (adapter.isLeExtendedAdvertisingSupported()) settings.setLegacy(false);
        ScanSettings scanSettings = settings.build();
        scanner.startScan(null, scanSettings, callback);
        if (!filters.isEmpty()) {
            filteredCallback = bleCallback(token, true);
            scanner.startScan(filters, scanSettings, filteredCallback);
        } else listener.status(Mode.BLE, "BLE 검색 중 · 선택한 규칙의 화면 꺼짐용 필터 없음");
        long duration = routes.size() > 1 ? cycles == 0 ? 12_000 : 60_000 : singleBleDuration;
        if (duration > 0) handler.postDelayed(() -> {
            if (running() && generation == token && phase == Mode.BLE) {
                finishPath(PathState.COMPLETE, "BLE 스캔 구간 완료");
            }
        }, duration);
    }

    private ScanCallback bleCallback(int token, boolean filtered) {
        return new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                handler.post(() -> { if (current(this, token, filtered)) readBle(result); });
            }
            @Override public void onBatchScanResults(List<ScanResult> results) {
                handler.post(() -> {
                    for (ScanResult result : results) {
                        if (!current(this, token, filtered)) break;
                        readBle(result);
                    }
                });
            }
            @Override public void onScanFailed(int code) {
                handler.post(() -> {
                    if (!current(this, token, filtered)) return;
                    if (filtered) filteredFailed = true;
                    else primaryFailed = true;
                    if (primaryFailed && filteredFailed) finishPath(PathState.FAILED, "BLE 요청 실패 (" + code + ")");
                    else setPath(Mode.BLE, PathState.RUNNING, filtered
                            ? "BLE 검색 중 · 화면 꺼짐 필터 요청 실패" : "BLE 검색 중 · 필터된 신호만 수신");
                });
            }
        };
    }

    private boolean current(ScanCallback value, int token, boolean filtered) {
        return running() && token == generation && (filtered ? filteredCallback == value : callback == value);
    }

    private List<ScanFilter> bleFilters() {
        List<ScanFilter> filters = new ArrayList<>();
        String[] uuids = config == null ? DeviceMatcher.bleServiceFilters() : DeviceMatcher.bleServiceFilters(config);
        int[] companies = config == null ? DeviceMatcher.bleCompanyFilters() : DeviceMatcher.bleCompanyFilters(config);
        for (String uuid : uuids) addUuidFilters(filters, uuid);
        for (int company : companies) filters.add(new ScanFilter.Builder().setManufacturerData(company, new byte[0]).build());
        if (config != null) {
            for (String name : DeviceMatcher.bleNameFilters(config)) filters.add(new ScanFilter.Builder().setDeviceName(name).build());
            for (DetectionRule rule : config.rules().rules()) {
                if (config.categories().contains(rule.category()) && rule.source().accepts(Mode.BLE)
                        && rule.field() == DetectionRule.Field.SERVICE_DATA_KEY) {
                    filters.add(new ScanFilter.Builder().setServiceData(ParcelUuid.fromString(rule.value()), null).build());
                }
            }
        } else {
            for (String uuid : activeTargets.uuids()) addUuidFilters(filters, uuid);
            for (String company : activeTargets.manufacturerIds()) {
                filters.add(new ScanFilter.Builder().setManufacturerData(Integer.parseInt(company.substring(2), 16), new byte[0]).build());
            }
            for (String name : activeTargets.names()) filters.add(new ScanFilter.Builder().setDeviceName(name).build());
        }
        return filters;
    }

    private void addUuidFilters(List<ScanFilter> filters, String uuid) {
        ParcelUuid value = ParcelUuid.fromString(uuid);
        filters.add(new ScanFilter.Builder().setServiceUuid(value).build());
        filters.add(new ScanFilter.Builder().setServiceData(value, new byte[0]).build());
    }

    private void readBle(ScanResult result) {
        try {
            BluetoothDevice device = result.getDevice();
            ScanRecord record = result.getScanRecord();
            String advertised = record == null ? null : record.getDeviceName();
            String cached = device.getName();
            boolean hasAdvertised = advertised != null && !advertised.trim().isEmpty();
            String name = hasAdvertised ? advertised : cached;
            Set<Integer> companies = new HashSet<>();
            Set<String> uuids = new HashSet<>();
            Set<String> keys = new HashSet<>();
            Map<Integer, String> manufacturerData = new LinkedHashMap<>();
            Map<String, String> serviceData = new LinkedHashMap<>();
            if (record != null) {
                SparseArray<byte[]> ids = record.getManufacturerSpecificData();
                if (ids != null) for (int i = 0; i < ids.size(); i++) {
                    companies.add(ids.keyAt(i));
                    manufacturerData.put(ids.keyAt(i), hex(ids.valueAt(i)));
                }
                if (record.getServiceUuids() != null) {
                    for (ParcelUuid uuid : record.getServiceUuids()) uuids.add(uuid.toString());
                }
                if (record.getServiceData() != null) {
                    for (Map.Entry<ParcelUuid, byte[]> entry : record.getServiceData().entrySet()) {
                        keys.add(entry.getKey().toString());
                        serviceData.put(entry.getKey().toString(), hex(entry.getValue()));
                    }
                }
            }
            long timestamp = result.getTimestampNanos() / 1_000_000;
            boolean previous = !currentBleObservation(timestamp, sessionStarted, routeStarted, SystemClock.elapsedRealtime());
            listener.found(new Signal(Mode.BLE, device.getAddress(), name, result.getRssi(), companies, uuids, keys,
                    manufacturerData, serviceData, advertised, cached, hasAdvertised ? NameOrigin.ADVERTISEMENT
                            : cached == null ? NameOrigin.UNKNOWN : NameOrigin.CACHE,
                    timestamp, null, null, record == null ? null : appearance(record.getBytes()), previous));
        } catch (SecurityException revoked) {
            finishPath(PathState.PERMISSION_REQUIRED, "BLE 검색 중 Bluetooth 권한 변경");
        }
    }

    // startNext verifies this route's permissions and handles runtime permission revocation.
    @SuppressLint("MissingPermission")
    private void startClassic(int token) {
        if (adapter != null && adapter.isDiscovering()) {
            finishPath(PathState.NOT_RUN, "Classic 검색 미실행 · 다른 검색 진행 중");
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        register(filter, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (receiver != this || token != generation) return;
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                    ownsDiscovery = false;
                    finishPath(PathState.COMPLETE, "Classic 검색 완료");
                    return;
                }
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) return;
                try {
                    String responseName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                    String cached = device.getName();
                    boolean response = responseName != null && !responseName.trim().isEmpty();
                    short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    BluetoothClass deviceClass = intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS);
                    listener.found(new Signal(Mode.CLASSIC, device.getAddress(), response ? responseName : cached,
                            rssi == Short.MIN_VALUE ? null : (int) rssi, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(),
                            null, cached, response ? NameOrigin.CLASSIC_RESPONSE : cached == null ? NameOrigin.UNKNOWN : NameOrigin.CACHE,
                            SystemClock.elapsedRealtime(), deviceClass == null ? null : deviceClass.getDeviceClass(), null, null, false));
                } catch (SecurityException revoked) {
                    finishPath(PathState.PERMISSION_REQUIRED, "Classic 검색 중 Bluetooth 권한 변경");
                }
            }
        });
        ownsDiscovery = adapter != null && adapter.startDiscovery();
        if (!ownsDiscovery) {
            finishPath(PathState.FAILED, "Classic 검색 요청 거절");
            return;
        }
        handler.postDelayed(() -> {
            if (running() && generation == token) finishPath(PathState.FAILED, "Classic 검색 시간 초과");
        }, 25_000);
    }

    private void startWifi(int token) {
        // A route denied by the preflight can still run later, but an automatic session
        // makes only one Wi-Fi attempt after reaching this point (including OS throttling).
        wifiAttempted = true;
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        long now = SystemClock.elapsedRealtime();
        long interval = foreground() ? 120_000 : 1_800_000;
        if (lastWifiAttempt > 0 && now - lastWifiAttempt < interval) {
            readWifi(wifi, false);
            finishPath(PathState.THROTTLED, "Wi-Fi 검색 요청 간격 대기 · 이전 결과");
            return;
        }
        wifiRequestedAt = now;
        register(new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (receiver != this || token != generation) return;
                try {
                    boolean updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
                    WifiRead results = readWifi(wifi, updated);
                    boolean current = updated && (results.fresh() > 0 || results.total() == 0);
                    finishPath(current ? PathState.COMPLETE : PathState.STALE,
                            current ? results.fresh() > 0 ? "Wi-Fi AP 검색 완료" : "Wi-Fi 새 스캔 결과 없음"
                                    : updated ? "Wi-Fi 새 스캔 결과 없음 · 이전 결과" : "Wi-Fi 갱신 실패 · 이전 결과");
                } catch (SecurityException revoked) {
                    finishPath(PathState.PERMISSION_REQUIRED, "Wi-Fi 결과 읽기 권한 필요");
                }
            }
        });
        lastWifiAttempt = now;
        if (wifi == null || !wifi.startScan()) {
            if (wifi != null) readWifi(wifi, false);
            finishPath(PathState.THROTTLED, "Wi-Fi 검색 요청 제한 또는 실패");
            return;
        }
        handler.postDelayed(() -> {
            if (running() && generation == token) finishPath(PathState.FAILED, "Wi-Fi 검색 시간 초과");
        }, 25_000);
    }

    private record WifiRead(int total, int fresh) {}

    // Every caller is within startNext or the result receiver's SecurityException handler.
    @SuppressLint("MissingPermission")
    private WifiRead readWifi(WifiManager wifi, boolean updated) {
        if (wifi == null) return new WifiRead(0, 0);
        int total = 0;
        int fresh = 0;
        long now = SystemClock.elapsedRealtime();
        for (android.net.wifi.ScanResult result : wifi.getScanResults()) {
            if (result.BSSID == null) continue;
            total++;
            long observed = result.timestamp / 1_000;
            boolean current = updated && currentWifiObservation(observed, sessionStarted, wifiRequestedAt, now);
            if (current) fresh++;
            String ssid = Build.VERSION.SDK_INT >= 33 && result.getWifiSsid() != null
                    ? unquoteWifiSsid(result.getWifiSsid().toString()) : result.SSID;
            listener.found(new Signal(Mode.WIFI, result.BSSID, ssid, result.level, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(),
                    null, null, NameOrigin.WIFI_SSID, observed, null, result.frequency, null, !current));
        }
        return new WifiRead(total, fresh);
    }

    static boolean currentBleObservation(long observed, long sessionStarted, long routeStarted, long now) {
        return observed > 0 && observed >= sessionStarted && observed >= routeStarted
                && observed <= now + 1_000 && now - observed <= 30_000;
    }

    static boolean currentWifiObservation(long observed, long sessionStarted, long requested, long now) {
        return observed > 0 && observed >= sessionStarted && observed >= requested
                && observed <= now + 1_000 && now - observed <= 30_000;
    }

    static String unquoteWifiSsid(String representation) {
        if (representation != null && representation.length() >= 2
                && representation.charAt(0) == '"' && representation.charAt(representation.length() - 1) == '"') {
            return representation.substring(1, representation.length() - 1);
        }
        return representation;
    }

    static Integer appearance(byte[] data) {
        if (data == null) return null;
        int offset = 0;
        while (offset < data.length) {
            int length = data[offset] & 0xFF;
            if (length == 0 || offset + length >= data.length) break;
            if (length >= 3 && (data[offset + 1] & 0xFF) == 0x19) {
                return (data[offset + 2] & 0xFF) | (data[offset + 3] & 0xFF) << 8;
            }
            offset += length + 1;
        }
        return null;
    }

    private boolean foreground() {
        ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(process);
        return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "";
        char[] digits = "0123456789ABCDEF".toCharArray();
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(digits[(value & 0xFF) >>> 4]).append(digits[value & 0x0F]);
        return result.toString();
    }

    private void register(IntentFilter filter, BroadcastReceiver next) {
        ContextCompat.registerReceiver(context, next, filter, ContextCompat.RECEIVER_EXPORTED);
        receiver = next;
    }

    private void releaseDiscovery() {
        if (receiver != null) {
            context.unregisterReceiver(receiver);
            receiver = null;
        }
        try {
            if (adapter != null && ownsDiscovery) adapter.cancelDiscovery();
        } catch (SecurityException | IllegalStateException ignored) {
            // A route may end after its permission or radio changes.
        }
        ownsDiscovery = false;
    }

    private void stopBle() {
        ScanCallback current = callback;
        ScanCallback filtered = filteredCallback;
        callback = null;
        filteredCallback = null;
        try {
            if (scanner != null && current != null) scanner.stopScan(current);
            if (scanner != null && filtered != null) scanner.stopScan(filtered);
        } catch (SecurityException | IllegalStateException ignored) {
            // Radio access can be revoked during an observation interval.
        }
        scanner = null;
    }

    void stop(Outcome outcome, String message) {
        if (!running()) return;
        mode = null;
        ++generation;
        handler.removeCallbacksAndMessages(null);
        stopBle();
        releaseDiscovery();
        if (phase != null && states.get(phase).state() == PathState.RUNNING) {
            setPath(phase, outcome == Outcome.COMPLETED ? PathState.COMPLETE : PathState.STOPPED, message);
        }
        adapter = null;
        listener.ended(outcome, message);
    }
}
