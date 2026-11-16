package com.glassesscanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ScanService extends Service implements RadioScanner.Listener {
    static final String SCAN_CHANNEL = "scan_status";
    static final String ALERT_CHANNEL = "glasses_candidates";
    static final String EXTRA_SESSION = "session";
    static final String EXTRA_OBSERVATION = "observation";
    private static final String STOP = "com.glassesscanner.STOP_SCAN";
    private static final String DISMISS = "com.glassesscanner.DISMISS_PROGRESS";
    private static final int RUNNING_ID = 100;
    private static final int CANDIDATE_ID = 101;
    private static final int SUMMARY_ID = 102;
    private static final int OBSERVATION_LIMIT = 1500;
    private static final int NOTIFICATION_LIMIT = 20;
    private static final long CHECKPOINT_INTERVAL = 15_000;

    enum State {
        IDLE, STARTING, RUNNING, COMPLETED, STOPPED, FAILED, INTERRUPTED;
        boolean active() { return this == STARTING || this == RUNNING; }
    }

    record Snapshot(String id, State state, RadioScanner.Mode mode, RadioScanner.Mode phase, String message,
                    long started, long duration, boolean background, List<ScanObservation> observations,
                    int candidates, boolean capacityReached) {
        static Snapshot idle() {
            return new Snapshot(null, State.IDLE, RadioScanner.Mode.BLE, RadioScanner.Mode.BLE,
                    "", 0, 0, false, Collections.emptyList(), 0, false);
        }
    }

    interface Observer {
        void changed(Snapshot snapshot);
        default boolean candidate(ScanObservation row) { return false; }
    }

    final class LocalBinder extends Binder {
        ScanService service() { return ScanService.this; }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, ScanObservation> observations = new LinkedHashMap<>();
    private final Map<String, SignalTracker> signalTrackers = new LinkedHashMap<>();
    private final Set<Observer> observers = new HashSet<>();
    private final Set<String> notifiedCandidates = new HashSet<>();
    private final Runnable publishTask = this::publish;
    private final Runnable progressTask = this::updateProgress;
    private final Runnable checkpointTask = this::checkpoint;
    private CandidateOverlay overlay;
    private RadioScanner scanner;
    private ScanHistory history;
    private ScanPreferences preferences;
    private ScanRecovery recovery;
    private PowerManager.WakeLock cpuLock;
    private boolean destroying;
    private String recoveredFrom;
    private NotificationManager notifications;
    private SavedTargets.Snapshot rules;
    private RadioScanner.Mode mode = RadioScanner.Mode.BLE;
    private RadioScanner.Mode phase = RadioScanner.Mode.BLE;
    private State state = State.IDLE;
    private String message = "";
    private String id;
    private long startedWall;
    private long startedElapsed;
    private long duration;
    private boolean background;
    private boolean foreground;
    private boolean progressDismissed;
    private boolean dirty;
    private boolean capacityReached;
    private boolean startedByIntent;
    private int candidates;
    private int alerts;

    @Override public void onCreate() {
        super.onCreate();
        overlay = new CandidateOverlay(this);
        scanner = new RadioScanner(this, this);
        history = ScanHistory.get(this);
        preferences = new ScanPreferences(this);
        recovery = new ScanRecovery(this);
        notifications = getSystemService(NotificationManager.class);
        createChannels(this);
    }

    static void createChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                SCAN_CHANNEL, context.getString(R.string.scan_channel), NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alerts = new NotificationChannel(
                ALERT_CHANNEL, context.getString(R.string.candidate_channel), NotificationManager.IMPORTANCE_HIGH);
        alerts.enableVibration(true);
        alerts.setVibrationPattern(new long[]{0, 300, 150, 300});
        alerts.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        manager.createNotificationChannel(alerts);
    }

    static boolean channelEnabled(Context context, String id) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(id);
        return manager.areNotificationsEnabled() && channel != null
                && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static Intent startIntent(Context context, RadioScanner.Mode mode, long duration) {
        return new Intent(context, ScanService.class).putExtra("mode", mode.name()).putExtra("duration", duration);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    void attach(Observer observer) {
        overlay.dismiss();
        observers.add(observer);
        observer.changed(snapshot());
    }

    void detach(Observer observer) { observers.remove(observer); }

    void dismissOverlay() { overlay.dismiss(); }

    void clear() {
        if (state.active()) return;
        observations.clear();
        signalTrackers.clear();
        candidates = 0;
        capacityReached = false;
        state = State.IDLE;
        message = "";
        publish();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            restoreScan(startId);
            return restartPolicy();
        }
        if (DISMISS.equals(intent.getAction())) {
            if (!state.active()) restoreScan(startId);
            if (state.active() && sessionMatches(intent.getStringExtra(EXTRA_SESSION))) progressDismissed = true;
            return restartPolicy();
        }
        if (STOP.equals(intent.getAction())) {
            String session = intent.getStringExtra(EXTRA_SESSION);
            if (state.active()) {
                if (background && sessionMatches(session)) stopScan();
            } else {
                ScanRecovery.Request saved = recovery.load();
                if (saved != null && saved.session().equals(session)) {
                    recovery.clear();
                    stopSelfResult(startId);
                } else restoreScan(startId);
            }
            return restartPolicy();
        }
        if (state.active()) return restartPolicy();
        String requested = intent.getStringExtra("mode");
        if (requested == null || !PermissionSetup.ready(this)) {
            recovery.clear();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        try {
            RadioScanner.Mode requestedMode = RadioScanner.Mode.valueOf(requested);
            startedByIntent = true;
            recoveredFrom = null;
            start(requestedMode, Math.max(0, intent.getLongExtra("duration", 0)), true);
        } catch (IllegalArgumentException invalid) {
            recovery.clear();
            stopSelfResult(startId);
        }
        return restartPolicy();
    }

    private int restartPolicy() { return background && state.active() ? START_STICKY : START_NOT_STICKY; }

    private boolean sessionMatches(String session) {
        return session != null && (session.equals(id) || session.equals(recoveredFrom));
    }

    private String requestIdentity() { return recoveredFrom == null ? id : recoveredFrom; }

    private void restoreScan(int startId) {
        if (state.active()) return;
        ScanRecovery.Request saved = recovery.load();
        if (saved == null || !PermissionSetup.ready(this)) {
            recovery.clear();
            stopSelfResult(startId);
            return;
        }
        startedByIntent = true;
        recoveredFrom = saved.session();
        long remaining = saved.remaining(SystemClock.elapsedRealtime());
        if (saved.deadline() != 0 && remaining == 0) {
            recovery.clear();
            stopSelfResult(startId);
            return;
        }
        start(saved.mode(), remaining, true);
    }

    void startVisible(RadioScanner.Mode requested, long millis) {
        if (state.active()) return;
        recoveredFrom = null;
        start(requested, millis, false);
    }

    private void start(RadioScanner.Mode requested, long millis, boolean backgroundScan) {
        if (state.active()) return;
        if (!PermissionSetup.ready(this)) return;
        id = UUID.randomUUID().toString();
        mode = requested;
        phase = mode == RadioScanner.Mode.COMBINED || mode == RadioScanner.Mode.ALL ? RadioScanner.Mode.BLE : mode;
        duration = mode == RadioScanner.Mode.BLE || mode == RadioScanner.Mode.ALL ? millis : 0;
        startedWall = System.currentTimeMillis();
        startedElapsed = SystemClock.elapsedRealtime();
        background = backgroundScan;
        progressDismissed = false;
        overlay.dismiss();
        observations.clear();
        signalTrackers.clear();
        notifiedCandidates.clear();
        candidates = 0;
        alerts = 0;
        dirty = false;
        capacityReached = false;
        rules = new SavedTargets(this).snapshot();
        state = State.STARTING;
        message = recoveredFrom == null ? "스캔 준비 중" : getString(R.string.background_restore);
        history.begin(id, startedWall, mode.label + (recoveredFrom == null ? "" : " · 자동 복구"));
        try {
            if (background && !recovery.remember(mode, duration, requestIdentity())) {
                ended(RadioScanner.Outcome.FAILED, "스캔 복구 설정을 저장하지 못했습니다.");
                startedByIntent = false;
                stopSelf();
                return;
            }
            if (!background) recovery.clear();
            if (background) {
                Notification notification = progressNotification();
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(RUNNING_ID, notification, ScanPolicy.serviceType(mode));
                } else {
                    startForeground(RUNNING_ID, notification);
                }
                foreground = true;
                holdCpu();
            }
            publish();
            scanner.start(mode, millis, rules);
            if (state.active()) handler.postDelayed(checkpointTask, CHECKPOINT_INTERVAL);
        } catch (SecurityException | IllegalStateException unavailable) {
            boolean stopBeforeForegroundDeadline = startedByIntent && !foreground;
            if (scanner.running()) {
                scanner.stop(RadioScanner.Outcome.FAILED, "스캔을 시작할 수 없습니다. 기기 권한과 설정을 확인하세요.");
            } else {
                ended(RadioScanner.Outcome.FAILED, "스캔을 시작할 수 없습니다. 기기 권한과 설정을 확인하세요.");
            }
            if (stopBeforeForegroundDeadline) {
                startedByIntent = false;
                stopSelf();
            }
        }
    }

    void stopScan() {
        if (scanner.running()) scanner.stop(RadioScanner.Outcome.STOPPED, "사용자가 스캔을 중지했습니다.");
    }

    void screenHidden() {
        if (!background && scanner.running()) {
            scanner.stop(RadioScanner.Outcome.STOPPED, "화면을 벗어나 스캔을 중지했습니다.");
        }
    }

    @Override public void status(RadioScanner.Mode currentPhase, String text) {
        state = State.RUNNING;
        phase = currentPhase;
        message = text;
        publish();
        updateProgress();
    }

    @Override public void found(RadioScanner.Signal signal) {
        if (!state.active()) return;
        String key = ScanObservation.key(signal);
        ScanObservation before = observations.get(key);
        RadioScanner.Signal merged = before == null ? signal : merge(before.signal(), signal);
        if (before != null) recordSignal(key, signal.rssi());
        if (before != null && before.signal().equals(merged)) return;
        List<GlassesMatcher.Match> matches = before != null && sameEvidence(before.signal(), merged)
                ? before.matches() : match(merged, rules);
        ScanObservation row = new ScanObservation(merged, matches);
        if (before == null && observations.size() >= OBSERVATION_LIMIT) {
            boolean firstLimit = !capacityReached;
            capacityReached = true;
            if (!row.candidate() || !evictNonCandidate()) {
                if (firstLimit) schedulePublish();
                return;
            }
        }
        observations.put(key, row);
        if (before == null) recordSignal(key, signal.rssi());
        dirty = true;
        boolean wasCandidate = before != null && before.candidate();
        if (row.candidate() != wasCandidate) {
            candidates += row.candidate() ? 1 : -1;
            if (row.candidate()) notifyCandidate(row);
            if (foreground && !progressPending) {
                progressPending = true;
                handler.postDelayed(progressTask, 750);
            }
        }
        schedulePublish();
    }

    private void recordSignal(String key, Integer rssi) {
        signalTrackers.computeIfAbsent(key, ignored -> new SignalTracker()).add(rssi, SystemClock.elapsedRealtime());
    }

    SignalTracker.Reading signalReading(String key, String session) {
        if (id == null || !id.equals(session)) return null;
        SignalTracker tracker = signalTrackers.get(key);
        return tracker == null ? null : tracker.reading(SystemClock.elapsedRealtime());
    }

    private boolean evictNonCandidate() {
        var entries = observations.values().iterator();
        while (entries.hasNext()) {
            ScanObservation row = entries.next();
            if (!row.candidate()) {
                signalTrackers.remove(row.key());
                entries.remove();
                return true;
            }
        }
        return false;
    }

    private void schedulePublish() {
        if (observers.isEmpty() || publishPending) return;
        publishPending = true;
        handler.postDelayed(publishTask, 500);
    }

    private boolean publishPending;
    private boolean progressPending;

    private Snapshot snapshot() {
        return new Snapshot(id, state, mode, phase, message, startedElapsed, duration, background,
                new ArrayList<>(observations.values()), candidates, capacityReached);
    }

    private void publish() {
        handler.removeCallbacks(publishTask);
        publishPending = false;
        if (observers.isEmpty()) return;
        Snapshot snapshot = snapshot();
        for (Observer observer : new ArrayList<>(observers)) observer.changed(snapshot);
    }

    private void updateProgress() {
        progressPending = false;
        handler.removeCallbacks(progressTask);
        if (foreground && !progressDismissed && state.active() && channelEnabled(this, SCAN_CHANNEL)) {
            notifications.notify(RUNNING_ID, progressNotification());
        }
    }

    private Notification progressNotification() {
        String body = phase.label + " · 후보 " + candidates + "건";
        if (phase != mode) body = mode.label + " · " + body;
        String status = message + " · 후보 " + candidates + "건";
        Intent stop = new Intent(this, ScanService.class).setAction(STOP)
                .setData(Uri.parse("glassesscanner://stop/" + id)).putExtra(EXTRA_SESSION, requestIdentity());
        Intent dismiss = new Intent(this, ScanService.class).setAction(DISMISS)
                .setData(Uri.parse("glassesscanner://dismiss/" + id)).putExtra(EXTRA_SESSION, requestIdentity());
        Notification.Builder builder = new Notification.Builder(this, SCAN_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_scan)
                .setContentTitle(getString(R.string.scan_notification))
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(status))
                .setWhen(startedWall)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setContentIntent(openApp(null))
                .setDeleteIntent(PendingIntent.getService(this, 0, dismiss, PendingIntent.FLAG_IMMUTABLE))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.scan_stop),
                        PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE)).build());
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private PendingIntent openApp(String key) {
        Uri.Builder route = new Uri.Builder().scheme("glassesscanner").authority("scan").appendPath(id);
        if (key != null) route.appendPath(key);
        Intent intent = new Intent(this, ScannerActivity.class).setData(route.build())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SESSION, id).putExtra(EXTRA_OBSERVATION, key);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    private void notifyCandidate(ScanObservation row) {
        if (!preferences.alerts() || !notifiedCandidates.add(row.candidateIdentity())) return;
        boolean shownInApp = false;
        for (Observer observer : new ArrayList<>(observers)) {
            shownInApp |= observer.candidate(row);
        }
        if (!shownInApp && preferences.overlayAlerts()) {
            Intent destination = new Intent(this, ScannerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_SESSION, id).putExtra(EXTRA_OBSERVATION, row.key());
            overlay.show(getString(R.string.candidate_warning_body, row.name(), row.best().reason()), destination);
        }
        // Visual warnings remain available even if system notifications are disabled.
        if (!channelEnabled(this, ALERT_CHANNEL)) return;
        String group = "candidates/" + id;
        alerts++;
        if (alerts > NOTIFICATION_LIMIT) {
            notifications.cancel(group + "/" + (alerts - NOTIFICATION_LIMIT), CANDIDATE_ID);
        }
        Notification publicVersion = new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_scan).setContentTitle(getString(R.string.notification_summary)).build();
        Notification candidate = new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_scan)
                .setContentTitle(row.best().label())
                .setContentText(row.best().reason())
                .setStyle(new Notification.BigTextStyle().bigText(row.best().reason()))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setContentIntent(openApp(row.key()))
                .setGroup(group)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .build();
        notifications.notify(group + "/" + alerts, CANDIDATE_ID, candidate);
        if (alerts == 1) return;
        Notification summary = new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_scan)
                .setContentTitle(getString(R.string.candidate_notification, alerts))
                .setContentText(getString(R.string.notification_summary))
                .setContentIntent(openApp(null))
                .setGroup(group)
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build();
        notifications.notify(group, SUMMARY_ID, summary);
    }

    @Override public void ended(RadioScanner.Outcome outcome, String text) {
        if (!state.active()) return;
        if (!destroying) recovery.clear();
        releaseCpu();
        state = State.valueOf(outcome.name());
        message = text;
        handler.removeCallbacks(checkpointTask);
        handler.removeCallbacks(progressTask);
        progressPending = false;
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        String completedId = id;
        save(System.currentTimeMillis(), success -> {
            if (!completedId.equals(id) || state.active()) return;
            if (!Boolean.TRUE.equals(success)) {
                message = text + " · 이력을 저장하지 못했습니다.";
                publish();
            }
            if (startedByIntent && !destroying) {
                startedByIntent = false;
                stopSelf();
            }
        });
        publish();
    }

    private void checkpoint() {
        if (!state.active()) return;
        if (!PermissionSetup.ready(this)) {
            scanner.stop(RadioScanner.Outcome.STOPPED, "필수 권한이 해제되어 스캔을 중지했습니다.");
            return;
        }
        try {
            ScanPolicy.Problem problem = ScanPolicy.radioProblem(this, mode);
            if (problem != null) {
                scanner.stop(RadioScanner.Outcome.FAILED, problem.message());
                return;
            }
            if (!ScanPolicy.missingPermissions(this, mode).isEmpty()) {
                scanner.stop(RadioScanner.Outcome.FAILED, "스캔에 필요한 권한이 해제되었습니다.");
                return;
            }
            if (background) holdCpu();
        } catch (SecurityException revoked) {
            scanner.stop(RadioScanner.Outcome.FAILED, "스캔에 필요한 권한이 해제되었습니다.");
            return;
        }
        if (dirty) {
            dirty = false;
            String checkpointId = id;
            save(0, success -> {
                if (!Boolean.TRUE.equals(success) && checkpointId.equals(id) && state.active()) dirty = true;
            });
        }
        handler.postDelayed(checkpointTask, CHECKPOINT_INTERVAL);
    }

    private void save(long ended, ScanHistory.Callback<Boolean> callback) {
        List<ScanObservation> retained = new ArrayList<>(ScanHistory.MAX_ROWS);
        for (boolean candidate : new boolean[]{true, false}) {
            for (ScanObservation row : observations.values()) {
                if (retained.size() == ScanHistory.MAX_ROWS) break;
                if (row.candidate() == candidate) retained.add(row);
            }
        }
        history.save(id, ended, state.name().toLowerCase(Locale.ROOT), message, observations.size(), candidates,
                observations.size() - retained.size(), retained, callback);
    }

    @Override public void onDestroy() {
        destroying = true;
        overlay.dismiss();
        observers.clear();
        if (scanner.running()) scanner.stop(RadioScanner.Outcome.INTERRUPTED, "스캔 서비스가 종료되었습니다.");
        handler.removeCallbacksAndMessages(null);
        releaseCpu();
        super.onDestroy();
    }

    private void holdCpu() {
        if (cpuLock == null) {
            PowerManager power = getSystemService(PowerManager.class);
            cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassesScanner:Scan");
            cpuLock.setReferenceCounted(false);
        }
        // Renew a bounded lease while the foreground scan is active.
        cpuLock.acquire(10 * 60_000L);
    }

    private void releaseCpu() {
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
    }

    static List<GlassesMatcher.Match> match(RadioScanner.Signal signal, SavedTargets.Snapshot targets) {
        List<GlassesMatcher.Match> matches = signal.source() == RadioScanner.Mode.WIFI
                ? GlassesMatcher.classifyWifi(signal.name())
                : GlassesMatcher.classify(signal.name(), signal.companies(),
                        signal.advertisedUuids(), signal.serviceDataKeys());
        if (signal.name() != null && targets.names().stream().anyMatch(signal.name().trim()::equalsIgnoreCase)) {
            matches.add(new GlassesMatcher.Match("등록한 탐색 대상", "저장한 이름과 일치", 3));
        }
        if (signal.advertisedUuids().stream().anyMatch(targets.uuids()::contains)
                || signal.serviceDataKeys().stream().anyMatch(targets.uuids()::contains)) {
            matches.add(new GlassesMatcher.Match("등록한 탐색 대상", "저장한 서비스 UUID와 일치", 3));
        }
        if (signal.companies().stream().anyMatch(company -> targets.manufacturerIds().contains(ScanObservation.company(company)))) {
            matches.add(new GlassesMatcher.Match("등록한 탐색 대상 후보", "저장한 제조사 ID와 일치 · 모델 미확인", 1));
        }
        matches.sort(Comparator.comparingInt(GlassesMatcher.Match::strength).reversed());
        return Collections.unmodifiableList(matches);
    }

    private static boolean sameEvidence(RadioScanner.Signal before, RadioScanner.Signal after) {
        return java.util.Objects.equals(before.name(), after.name()) && before.companies().equals(after.companies())
                && before.advertisedUuids().equals(after.advertisedUuids())
                && before.serviceDataKeys().equals(after.serviceDataKeys());
    }

    private static RadioScanner.Signal merge(RadioScanner.Signal before, RadioScanner.Signal current) {
        return new RadioScanner.Signal(current.source(), current.address(),
                current.name() == null || current.name().trim().isEmpty() ? before.name() : current.name(),
                current.rssi() == null ? before.rssi() : current.rssi(), union(before.companies(), current.companies()),
                union(before.advertisedUuids(), current.advertisedUuids()),
                union(before.serviceDataKeys(), current.serviceDataKeys()),
                mergeData(before.manufacturerData(), current.manufacturerData()),
                mergeData(before.serviceData(), current.serviceData()));
    }

    private static <K> Map<K, String> mergeData(Map<K, String> before, Map<K, String> current) {
        Map<K, String> values = new LinkedHashMap<>(before);
        values.putAll(current);
        return values;
    }

    private static <T> Set<T> union(Set<T> before, Set<T> current) {
        if (before.containsAll(current)) return before;
        Set<T> values = new HashSet<>(before);
        values.addAll(current);
        return values;
    }
}
