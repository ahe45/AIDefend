package com.glassesscanner;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.SparseArray;

import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

final class RadioScanner {
    enum Mode {
        BLE("BLE"), CLASSIC("Classic Bluetooth"), COMBINED("BLE + Classic"), WIFI("Wi-Fi AP"),
        ALL("통합 관측 · BLE + Classic + Wi-Fi AP");

        final String label;
        Mode(String label) { this.label = label; }
    }

    record Signal(Mode source, String address, String name, Integer rssi,
                  Set<Integer> companies, Set<String> advertisedUuids, Set<String> serviceDataKeys,
                  Map<Integer, String> manufacturerData, Map<String, String> serviceData) {
        Signal {
            companies = Collections.unmodifiableSet(new HashSet<>(companies));
            advertisedUuids = Collections.unmodifiableSet(new HashSet<>(advertisedUuids));
            serviceDataKeys = Collections.unmodifiableSet(new HashSet<>(serviceDataKeys));
            manufacturerData = Collections.unmodifiableMap(new LinkedHashMap<>(manufacturerData));
            serviceData = Collections.unmodifiableMap(new LinkedHashMap<>(serviceData));
        }

        Signal(Mode source, String address, String name, Integer rssi, Set<Integer> companies,
               Set<String> advertisedUuids, Set<String> serviceDataKeys) {
            this(source, address, name, rssi, companies, advertisedUuids, serviceDataKeys,
                    Collections.emptyMap(), Collections.emptyMap());
        }
    }

    enum Outcome { COMPLETED, STOPPED, FAILED, INTERRUPTED }

    interface Listener {
        void status(Mode phase, String text);
        void found(Signal signal);
        void ended(Outcome outcome, String message);
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Mode mode;
    private String bleWarning;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private ScanCallback filteredCallback;
    private boolean primaryFailed;
    private boolean filteredFailed;
    private BroadcastReceiver receiver;
    private boolean ownsDiscovery;
    private int generation;
    private long cycleStarted;
    private long lastWifiAttempt;
    private Mode phase;
    private int cycles;
    private SavedTargets.Snapshot activeTargets;

    RadioScanner(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean running() { return mode != null; }

    void start(Mode requested, long bleDuration, SavedTargets.Snapshot targets) {
        if (running()) return;
        mode = requested;
        activeTargets = targets;
        cycles = 0;
        bleWarning = null;
        int token = ++generation;
        if (requested == Mode.ALL && bleDuration > 0) {
            handler.postDelayed(() -> stop(Outcome.COMPLETED, "통합 관측 시간 종료"), bleDuration);
        }
        try {
            if (mode == Mode.WIFI) {
                startWifi();
                return;
            }
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            adapter = manager == null ? null : manager.getAdapter();
            if (mode == Mode.ALL) {
                startAllBle();
            } else if (adapter == null || !adapter.isEnabled()) {
                stop(Outcome.FAILED, "블루투스를 켠 뒤 다시 스캔하세요.");
            } else if (mode == Mode.CLASSIC) {
                startClassic();
            } else {
                startBle(token, mode == Mode.COMBINED ? 12_000 : bleDuration, targets);
            }
        } catch (SecurityException | IllegalStateException failure) {
            stop(Outcome.FAILED, "스캔을 시작할 수 없습니다. 권한과 무선 설정을 확인하세요.");
        }
    }

    private void startAllBle() {
        if (mode != Mode.ALL) return;
        cycleStarted = SystemClock.elapsedRealtime();
        bleWarning = null;
        phase = Mode.BLE;
        try {
            if (adapter == null || !adapter.isEnabled()
                    || !context.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)) {
                finishBle(Outcome.FAILED, "BLE 사용 불가");
                return;
            }
            startBle(++generation, cycles++ == 0 ? 12_000 : 60_000, activeTargets);
        } catch (SecurityException | IllegalStateException unavailable) {
            finishBle(Outcome.FAILED, "BLE 권한 또는 무선 상태 확인 필요");
        }
    }

    private void finishClassic(Outcome outcome, String message) {
        if (mode != Mode.ALL) {
            stop(outcome, message);
            return;
        }
        releaseDiscovery();
        android.app.ActivityManager.RunningAppProcessInfo process = new android.app.ActivityManager.RunningAppProcessInfo();
        android.app.ActivityManager.getMyMemoryState(process);
        long interval = process.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                ? 120_000 : 1_800_000;
        if (lastWifiAttempt == 0 || SystemClock.elapsedRealtime() - lastWifiAttempt >= interval) {
            phase = Mode.WIFI;
            try {
                startWifi();
            } catch (SecurityException | IllegalStateException unavailable) {
                finishWifi(Outcome.FAILED, "Wi-Fi 권한 또는 무선 상태 확인 필요");
            }
        } else {
            repeatAll(message + " · Wi-Fi 요청 간격 대기");
        }
    }

    private void finishWifi(Outcome outcome, String message) {
        if (mode != Mode.ALL) {
            stop(outcome, message);
            return;
        }
        releaseDiscovery();
        repeatAll(message);
    }

    private void repeatAll(String message) {
        listener.status(phase, message + " · 통합 관측 계속");
        // Failed radios must not cause a tight loop or exceed Android's BLE start limit.
        long delay = Math.max(0, 30_000 - (SystemClock.elapsedRealtime() - cycleStarted));
        handler.postDelayed(this::startAllBle, delay);
    }

    private void startBle(int token, long duration, SavedTargets.Snapshot targets) {
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            finishBle(Outcome.FAILED, "BLE 스캐너를 사용할 수 없습니다.");
            return;
        }
        primaryFailed = false;
        filteredFailed = false;
        callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                handler.post(() -> {
                    if (callback == this && token == generation) readBle(result);
                });
            }

            @Override public void onBatchScanResults(List<ScanResult> results) {
                handler.post(() -> {
                    for (ScanResult result : results) {
                        if (callback != this || token != generation) break;
                        readBle(result);
                    }
                });
            }

            @Override public void onScanFailed(int code) {
                handler.post(() -> {
                    if (callback != this || token != generation) return;
                    primaryFailed = true;
                    if (filteredFailed) finishBle(Outcome.FAILED, "BLE 스캔 실패 (" + code + ")");
                    else listener.status(Mode.BLE, "BLE 스캔 중 · 필터가 적용된 신호만 수신합니다.");
                });
            }
        };
        ScanSettings.Builder settings = new ScanSettings.Builder()
                .setScanMode(duration == 0 ? ScanSettings.SCAN_MODE_BALANCED : ScanSettings.SCAN_MODE_LOW_LATENCY);
        if (adapter.isLeExtendedAdvertisingSupported()) settings.setLegacy(false);
        ScanSettings scanSettings = settings.build();
        List<ScanFilter> filters = prepareFilteredBle(token, targets);
        try {
            scanner.startScan(null, scanSettings, callback);
            scanner.startScan(filters, scanSettings, filteredCallback);
        } catch (SecurityException revoked) {
            finishBle(Outcome.FAILED, "BLE 스캔에 필요한 권한이 변경되었습니다.");
            return;
        }
        listener.status(Mode.BLE, "BLE 스캔 중");
        if (duration > 0) handler.postDelayed(() -> {
            if (callback != null && token == generation) finishBle(Outcome.COMPLETED, "BLE 스캔 완료");
        }, duration);
    }

    private List<ScanFilter> prepareFilteredBle(int token, SavedTargets.Snapshot targets) {
        List<ScanFilter> filters = new ArrayList<>();
        for (String uuid : GlassesMatcher.bleServiceFilters()) {
            ParcelUuid value = ParcelUuid.fromString(uuid);
            filters.add(new ScanFilter.Builder().setServiceUuid(value).build());
            filters.add(new ScanFilter.Builder().setServiceData(value, new byte[0]).build());
        }
        for (int id : GlassesMatcher.bleCompanyFilters()) {
            filters.add(new ScanFilter.Builder().setManufacturerData(id, new byte[0]).build());
        }
        for (String uuid : targets.uuids()) {
            ParcelUuid value = ParcelUuid.fromString(uuid);
            filters.add(new ScanFilter.Builder().setServiceUuid(value).build());
            filters.add(new ScanFilter.Builder().setServiceData(value, new byte[0]).build());
        }
        for (String company : targets.manufacturerIds()) {
            int id = Integer.parseInt(company.substring(2), 16);
            filters.add(new ScanFilter.Builder().setManufacturerData(id, new byte[0]).build());
        }
        for (String name : targets.names()) {
            filters.add(new ScanFilter.Builder().setDeviceName(name).build());
        }
        filteredCallback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                handler.post(() -> {
                    if (filteredCallback == this && token == generation) readBle(result);
                });
            }
            @Override public void onScanFailed(int code) {
                handler.post(() -> {
                    if (filteredCallback == this && token == generation) {
                        filteredFailed = true;
                        if (primaryFailed) finishBle(Outcome.FAILED, "BLE 스캔 실패 (" + code + ")");
                        else listener.status(Mode.BLE, "BLE 스캔 중 · 화면이 잠기면 신호 수신이 제한될 수 있습니다.");
                    }
                });
            }
        };
        return filters;
    }

    private void finishBle(Outcome outcome, String message) {
        if (mode != Mode.COMBINED && mode != Mode.ALL) {
            stop(outcome, message);
            return;
        }
        if (outcome == Outcome.FAILED) bleWarning = message;
        stopBle();
        phase = Mode.CLASSIC;
        try {
            startClassic();
        } catch (SecurityException | IllegalStateException failure) {
            finishClassic(Outcome.FAILED, message + " · Classic 검색을 시작할 수 없습니다.");
        }
    }

    private void readBle(ScanResult result) {
        try {
            BluetoothDevice device = result.getDevice();
            ScanRecord record = result.getScanRecord();
            String name = record == null ? null : record.getDeviceName();
            Set<Integer> companies = new HashSet<>();
            Set<String> uuids = new HashSet<>();
            Set<String> serviceDataKeys = new HashSet<>();
            Map<Integer, String> manufacturerData = new LinkedHashMap<>();
            Map<String, String> serviceData = new LinkedHashMap<>();
            if (record != null) {
                SparseArray<byte[]> ids = record.getManufacturerSpecificData();
                if (ids != null) for (int i = 0; i < ids.size(); i++) {
                    companies.add(ids.keyAt(i));
                    manufacturerData.put(ids.keyAt(i), hex(ids.valueAt(i)));
                }
                List<ParcelUuid> services = record.getServiceUuids();
                if (services != null) for (ParcelUuid uuid : services) uuids.add(uuid.toString());
                if (record.getServiceData() != null) {
                    for (Map.Entry<ParcelUuid, byte[]> entry : record.getServiceData().entrySet()) {
                        serviceDataKeys.add(entry.getKey().toString());
                        serviceData.put(entry.getKey().toString(), hex(entry.getValue()));
                    }
                }
            }
            if (name == null || name.trim().isEmpty()) name = device.getName();
            listener.found(new Signal(Mode.BLE, device.getAddress(), name, result.getRssi(), companies, uuids,
                    serviceDataKeys, manufacturerData, serviceData));
        } catch (SecurityException revoked) {
            finishBle(Outcome.FAILED, "스캔 중 블루투스 권한이 변경되었습니다.");
        }
    }

    private void startClassic() {
        if (adapter == null || !adapter.isEnabled()) {
            finishClassic(Outcome.FAILED, "Classic Bluetooth 사용 불가");
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        register(filter, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (receiver != this) return;
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                    ownsDiscovery = false;
                    finishClassic(bleWarning == null ? Outcome.COMPLETED : Outcome.FAILED,
                            bleWarning == null ? "Classic 검색 완료" : bleWarning + " · Classic 검색 완료");
                    return;
                }
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) return;
                try {
                    String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                    if (name == null || name.trim().isEmpty()) name = device.getName();
                    short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    listener.found(new Signal(Mode.CLASSIC, device.getAddress(), name,
                            rssi == Short.MIN_VALUE ? null : (int) rssi, java.util.Collections.emptySet(), java.util.Collections.emptySet(),
                            java.util.Collections.emptySet()));
                } catch (SecurityException revoked) {
                    finishClassic(Outcome.FAILED, "검색 중 블루투스 권한이 변경되었습니다.");
                }
            }
        });
        try {
            ownsDiscovery = adapter.startDiscovery();
        } catch (SecurityException revoked) {
            finishClassic(Outcome.FAILED, "Classic 검색에 필요한 권한이 변경되었습니다.");
            return;
        }
        if (!ownsDiscovery) {
            finishClassic(Outcome.FAILED, "Classic 검색 요청이 거절되었습니다.");
            return;
        }
        listener.status(Mode.CLASSIC, bleWarning == null ? "Classic 검색 중" : bleWarning + " · Classic 검색 중");
        BroadcastReceiver current = receiver;
        handler.postDelayed(() -> {
            if (receiver == current) finishClassic(Outcome.FAILED, "Classic 검색 시간 종료");
        }, 25_000);
    }

    private void startWifi() {
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        if (wifi == null || !wifi.isWifiEnabled()) {
            finishWifi(Outcome.FAILED, "Wi-Fi를 켜면 AP 검색도 수행됩니다.");
            return;
        }
        register(new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (receiver != this) return;
                if (!intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                    finishWifi(Outcome.FAILED, "새 Wi-Fi 결과를 받지 못했습니다.");
                    return;
                }
                try {
                    for (android.net.wifi.ScanResult result : wifi.getScanResults()) {
                        if (result.BSSID != null && result.timestamp > 0
                                && SystemClock.elapsedRealtime() - result.timestamp / 1000 <= 30_000) {
                            listener.found(new Signal(Mode.WIFI, result.BSSID,
                                result.SSID, result.level, java.util.Collections.emptySet(), java.util.Collections.emptySet(),
                                    java.util.Collections.emptySet()));
                        }
                    }
                    finishWifi(Outcome.COMPLETED, "Wi-Fi AP 검색 완료");
                } catch (SecurityException revoked) {
                    finishWifi(Outcome.FAILED, "Wi-Fi 결과를 읽으려면 위치 권한이 필요합니다.");
                }
            }
        });
        if (mode == Mode.ALL) lastWifiAttempt = SystemClock.elapsedRealtime();
        if (!wifi.startScan()) {
            finishWifi(Outcome.FAILED, "Wi-Fi 요청 제한 또는 무선 상태 확인 필요");
            return;
        }
        listener.status(Mode.WIFI, "Wi-Fi AP 검색 중");
        BroadcastReceiver current = receiver;
        handler.postDelayed(() -> {
            if (receiver == current) finishWifi(Outcome.FAILED, "Wi-Fi 검색 시간이 초과되었습니다.");
        }, mode == Mode.ALL ? 10_000 : 25_000);
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "";
        char[] digits = "0123456789ABCDEF".toCharArray();
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(digits[(value & 0xFF) >>> 4]).append(digits[value & 0x0F]);
        }
        return result.toString();
    }

    private void releaseDiscovery() {
        if (receiver != null) {
            context.unregisterReceiver(receiver);
            receiver = null;
        }
        try {
            if (adapter != null && ownsDiscovery) adapter.cancelDiscovery();
        } catch (SecurityException | IllegalStateException ignored) {
            // Radio access may have changed during this phase.
        }
        ownsDiscovery = false;
    }

    private void register(IntentFilter filter, BroadcastReceiver next) {
        ContextCompat.registerReceiver(context, next, filter, ContextCompat.RECEIVER_EXPORTED);
        receiver = next;
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
            // The radio or permission may already have changed.
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
        adapter = null;
        listener.ended(outcome, message);
    }
}

