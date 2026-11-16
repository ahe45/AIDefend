package com.glassesscanner;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationBarView;

import java.util.List;

public final class ScannerActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            ensurePermissions();
            screen.updateClock();
            handler.postDelayed(this, 1000);
        }
    };
    private final ActivityResultLauncher<String[]> scanPermissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (ScanPolicy.missingPermissions(this, this.preferences.mode()).isEmpty()) prepareScan();
                else this.screen.issue("선택한 모드를 사용하려면 기기 권한이 필요합니다.", "앱 권한 설정", this::appSettings);
            });
    private final ActivityResultLauncher<String> notificationsPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), allowed -> {
                renderSettings();
                if (this.notificationRequestedForScan) {
                    this.notificationRequestedForScan = false;
                    if (allowed) prepareScan();
                    else showVisibleOnly();
                }
            });

    private PermissionSetup permissionSetup;
    private ScanPreferences preferences;
    private ScanViewModel model;
    private ScanScreen screen;
    private HistoryScreen history;
    private TargetsEditor targets;
    private PhotoPreview photos;
    private MaterialToolbar toolbar;
    private NavigationBarView navigation;
    private ScanService service;
    private boolean bound;
    private boolean visible;
    private boolean stopRequested;
    private boolean notificationRequestedForScan;
    private boolean renderingSettings;
    private androidx.appcompat.app.AlertDialog candidateWarning;
    private ScanObservation warningRow;
    private final ActivityResultLauncher<Intent> overlayPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                preferences.overlayAlerts(Settings.canDrawOverlays(this));
                renderSettings();
            });
    private final ScanService.Observer observer = new ScanService.Observer() {
        @Override public void changed(ScanService.Snapshot snapshot) { receive(snapshot); }
        @Override public boolean candidate(ScanObservation row) { return showCandidateWarning(row); }
    };
    private final Runnable startTimeout = () -> {
        if (model.starting) {
            model.starting = false;
            screen.issue("스캔 서비스에 연결하지 못했습니다. 다시 시도하세요.", null, null);
        }
    };

    @Override protected void onCreate(Bundle state) {
        preferences = new ScanPreferences(this);
        getDelegate().setLocalNightMode(themeMode(preferences.theme()));
        setTheme(R.style.Theme_GlassesScanner);
        super.onCreate(state);
        photos = new PhotoPreview(this, state);
        model = new ViewModelProvider(this).get(ScanViewModel.class);
        if (state != null) {
            model.tab = state.getInt("tab", R.id.nav_scan);
            model.candidatesOnly = state.getBoolean("candidates", true);
            model.signalSort = state.getBoolean("signalSort");
            model.selected = state.getString("selected");
            model.selectedSession = state.getString("selectedSession");
            notificationRequestedForScan = state.getBoolean("notifyScan");
        }
        setContentView(R.layout.activity_scanner);
        ScanService.createChannels(this);
        installInsets();
        toolbar = findViewById(R.id.toolbar);
        navigation = findViewById(R.id.navigation);
        screen = new ScanScreen(this, model, preferences);
        if (state != null) screen.restoreScroll(state.getParcelable("list"));
        history = new HistoryScreen(this);
        targets = new TargetsEditor(this);
        navigation.setOnItemSelectedListener(item -> navigate(item.getItemId()));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (model.tab != R.id.nav_scan) select(R.id.nav_scan);
                else if (model.selected != null && screen.wide()) {
                    model.selected = null;
                    screen.render();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        bindSettings();
        permissionSetup = new PermissionSetup(this);
        select(model.tab);
        permissionSetup.refresh();
        if (state == null) acceptRoute(getIntent());
    }

    private void installInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(!dark);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(!dark);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        if (service != null) service.attach(observer);
        else if (!bound) {
            bound = bindService(new Intent(this, ScanService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) screen.issue("스캔 서비스에 연결할 수 없습니다.", "다시 연결", this::reconnect);
        }
        if (model.starting) handler.postDelayed(startTimeout, 6_000);
        handler.post(tick);
    }

    @Override protected void onResume() {
        super.onResume();
        if (service != null) service.dismissOverlay();
        renderSettings();
        screen.render();
        if (!ensurePermissions()) return;
        handleRoute();
        if (model.tab == R.id.nav_history) history.refresh();
        photos.restore();
    }

    @Override protected void onStop() {
        visible = false;
        if (candidateWarning != null) {
            candidateWarning.dismiss();
            candidateWarning = null;
        }
        if (service != null) {
            service.detach(observer);
            if (!isChangingConfigurations()) service.screenHidden();
        }
        handler.removeCallbacks(tick);
        handler.removeCallbacks(startTimeout);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (service != null) service.detach(observer);
        if (bound) unbindService(connection);
        photos.destroy(isChangingConfigurations());
        screen.close();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab", model.tab);
        state.putBoolean("candidates", model.candidatesOnly);
        state.putBoolean("signalSort", model.signalSort);
        state.putString("selected", model.selected);
        state.putString("selectedSession", model.selectedSession);
        state.putBoolean("notifyScan", notificationRequestedForScan);
        state.putParcelable("list", screen.saveScroll());
        photos.save(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptRoute(intent);
        if (service != null) handleRoute();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ScanService.LocalBinder) binder).service();
            if (visible) service.attach(observer);
            if (!PermissionSetup.ready(ScannerActivity.this)) service.stopScan();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            model.scan = ScanService.Snapshot.idle();
            model.starting = false;
            screen.issue("스캔 서비스 연결이 끊겼습니다. 다시 연결되면 스캔을 시작할 수 있습니다.", null, null);
        }

        @Override public void onBindingDied(ComponentName name) {
            onServiceDisconnected(name);
            reconnect();
        }
    };

    private void reconnect() {
        if (bound) unbindService(connection);
        bound = bindService(new Intent(this, ScanService.class), connection, Context.BIND_AUTO_CREATE);
    }

    private void receive(ScanService.Snapshot scan) {
        if (scan.id() != null && model.selectedSession != null && !scan.id().equals(model.selectedSession)) {
            model.selected = null;
            model.selectedSession = null;
        }
        model.scan = scan;
        if (scan.id() != null) {
            model.starting = false;
            handler.removeCallbacks(startTimeout);
            if (stopRequested && scan.state() == ScanService.State.RUNNING) {
                stopRequested = false;
                service.stopScan();
                return;
            }
            if (!scan.state().active()) stopRequested = false;
        }
        if (scan.state().active()) screen.issue(null, null, null);
        else screen.render();
        renderSettings();
        if (model.tab == R.id.nav_history && !scan.state().active()) history.refresh();
        handleRoute();
        if (model.tab == R.id.nav_scan && PermissionSetup.ready(this)) screen.restoreObservation();
    }

    private void acceptRoute(Intent intent) {
        model.routeSession = intent.getStringExtra(ScanService.EXTRA_SESSION);
        model.routeObservation = intent.getStringExtra(ScanService.EXTRA_OBSERVATION);
    }

    private void handleRoute() {
        if (!PermissionSetup.ready(this) || model.routeSession == null) return;
        String id = model.routeSession;
        String observation = model.routeObservation;
        model.routeSession = null;
        model.routeObservation = null;
        if (id.equals(model.scan.id()) && model.scan.state().active()) {
            select(R.id.nav_scan);
            ScanObservation row = screen.find(observation);
            if (row != null) openObservation(row);
            else if (observation != null) {
                model.routeSession = id;
                model.routeObservation = observation;
            }
        } else {
            select(R.id.nav_history);
            history.open(id);
        }
    }

    private void select(int id) {
        NavigationBarView view = navigation;
        if (view.getSelectedItemId() == id) navigate(id);
        else view.setSelectedItemId(id);
    }

    private boolean navigate(int id) {
        model.tab = id;
        findViewById(R.id.scan_screen).setVisibility(id == R.id.nav_scan ? View.VISIBLE : View.GONE);
        findViewById(R.id.history_screen).setVisibility(id == R.id.nav_history ? View.VISIBLE : View.GONE);
        findViewById(R.id.settings_screen).setVisibility(id == R.id.nav_settings ? View.VISIBLE : View.GONE);
        if (id == R.id.nav_history) {
            toolbar.setTitle(R.string.history);
            history.refresh();
        } else if (id == R.id.nav_settings) {
            toolbar.setTitle(R.string.settings);
            renderSettings();
        } else {
            toolbar.setTitle(R.string.app_name);
            screen.render();
        }
        if (permissionSetup != null) permissionSetup.refresh();
        return true;
    }

    void chooseMode() {
        if (busy()) return;
        RadioScanner.Mode[] modes = RadioScanner.Mode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < labels.length; i++) labels[i] = modes[i].label;
        new IconDialogBuilder(this).setTitle("스캔 모드")
                .setSingleChoiceItems(labels, preferences.mode().ordinal(), (dialog, index) -> {
                    preferences.mode(modes[index]);
                    screen.issue(null, null, null);
                    renderSettings();
                    dialog.dismiss();
                }).setNegativeButton("취소", null).show();
    }

    void chooseDuration() {
        if (busy()) return;
        new IconDialogBuilder(this).setTitle("BLE / 통합 관측 시간")
                .setSingleChoiceItems(ScanPreferences.DURATION_LABELS, preferences.duration(), (dialog, index) -> {
                    preferences.duration(index);
                    screen.render();
                    renderSettings();
                    dialog.dismiss();
                }).setNegativeButton("취소", null).show();
    }

    void chooseSort(View anchor) {
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "후보 우선");
        menu.getMenu().add(0, 1, 1, "신호 세기순");
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.getMenu().findItem(model.signalSort ? 1 : 0).setChecked(true);
        menu.setOnMenuItemClickListener(item -> {
            model.signalSort = item.getItemId() == 1;
            screen.render();
            return true;
        });
        menu.show();
    }

    private boolean busy() { return model.starting || model.scan.state().active(); }

    void scanAction() {
        if (!ensurePermissions()) return;
        if (busy()) {
            if (model.scan.state() == ScanService.State.RUNNING && service != null) service.stopScan();
            else stopRequested = true;
            return;
        }
        screen.issue(null, null, null);
        List<String> missing = ScanPolicy.missingPermissions(this, preferences.mode());
        if (missing.isEmpty()) {
            prepareScan();
            return;
        }
        String explanation = ScanPolicy.needsLocation(preferences.mode())
                ? "이 모드에서 주변 신호를 읽으려면 Android에서 정확한 위치 권한을 허용해야 합니다."
                : "이 모드에서 Bluetooth 기기를 찾으려면 주변 기기 권한을 허용해야 합니다.";
        new IconDialogBuilder(this).setTitle("스캔 권한").setMessage(explanation)
                .setPositiveButton("권한 요청", (dialog, which) -> scanPermissions.launch(missing.toArray(new String[0])))
                .setNeutralButton("앱 설정", (dialog, which) -> appSettings())
                .setNegativeButton("취소", null).show();
    }

    private void prepareScan() {
        RadioScanner.Mode mode = preferences.mode();
        try {
            List<ScanPolicy.Problem> problems = ScanPolicy.startupProblems(this, mode);
            if (!problems.isEmpty()) {
                showScanSetup(problems);
                return;
            }
        } catch (SecurityException changed) {
            screen.issue("스캔 권한이 변경되었습니다.", "앱 권한 설정", this::appSettings);
            return;
        }
        if (ScanService.channelEnabled(this, ScanService.SCAN_CHANNEL)) {
            start(true);
        } else if (Build.VERSION.SDK_INT >= 33 && !preferences.askedNotifications()
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            new IconDialogBuilder(this).setTitle("백그라운드 스캔 알림")
                    .setMessage("화면이 잠겨 있을 때도 진행 상태와 새 후보를 보려면 알림을 허용하세요.")
                    .setPositiveButton("알림 허용", (dialog, which) -> requestNotificationPermission(true))
                    .setNegativeButton("화면을 볼 때만", (dialog, which) -> start(false)).show();
        } else {
            showVisibleOnly();
        }
    }

    androidx.appcompat.app.AlertDialog showScanSetup(List<ScanPolicy.Problem> problems) {
        android.widget.LinearLayout actions = new android.widget.LinearLayout(this);
        actions.setOrientation(android.widget.LinearLayout.VERTICAL);
        int inset = getResources().getDimensionPixelSize(R.dimen.space_medium);
        actions.setPadding(inset, 0, inset, inset);
        TextView explanation = new TextView(this);
        explanation.setTextColor(getColor(R.color.on_surface));
        explanation.setTextSize(16);
        explanation.setPadding(0, 0, 0, inset);
        actions.addView(explanation);
        List<String> messages = new java.util.ArrayList<>();
        boolean needsModeChoice = false;
        for (ScanPolicy.Problem problem : problems) {
            messages.add("• " + problem.message());
            if (problem.action() == null) {
                needsModeChoice = true;
                continue;
            }
            com.google.android.material.button.MaterialButton button = new com.google.android.material.button.MaterialButton(this);
            int title;
            int icon;
            if (Settings.ACTION_BLUETOOTH_SETTINGS.equals(problem.action())) {
                title = R.string.scan_setup_bluetooth;
                icon = R.drawable.ic_permission_bluetooth;
            } else if (Settings.ACTION_WIFI_SETTINGS.equals(problem.action())) {
                title = R.string.scan_setup_wifi;
                icon = R.drawable.ic_wifi;
            } else {
                title = R.string.scan_setup_location;
                icon = R.drawable.ic_permission_location;
            }
            button.setText(title);
            button.setIconResource(icon);
            button.setOnClickListener(view -> openSettings(new Intent(problem.action())));
            actions.addView(button, new android.widget.LinearLayout.LayoutParams(-1, -2));
        }
        explanation.setText(getString(R.string.scan_setup_message, preferences.mode().label, String.join("\n", messages)));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(actions);
        MaterialAlertDialogBuilder builder = new IconDialogBuilder(this).setTitle(R.string.scan_setup_title)
                .setView(scroll).setPositiveButton(R.string.scan_setup_retry, null).setNegativeButton(R.string.cancel, null);
        if (needsModeChoice) builder.setNeutralButton(R.string.scan_setup_mode, (dialog, which) -> chooseMode());
        androidx.appcompat.app.AlertDialog dialog = builder.show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            dialog.dismiss();
            scanAction();
        });
        return dialog;
    }

    private void showVisibleOnly() {
        new IconDialogBuilder(this).setTitle("진행 알림이 꺼져 있습니다")
                .setMessage("백그라운드 스캔을 시작하려면 기기 설정에서 스캔 진행 알림을 허용하세요.")
                .setPositiveButton("알림 설정", (dialog, which) -> notificationSettings(ScanService.SCAN_CHANNEL))
                .setNeutralButton("화면을 볼 때만", (dialog, which) -> start(false))
                .setNegativeButton("취소", null).show();
    }

    private void requestNotificationPermission(boolean forScan) {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationRequestedForScan = forScan;
            preferences.askedNotifications(true);
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void start(boolean background) {
        if (!ensurePermissions() || busy()) return;
        if (service == null) {
            reconnect();
            screen.issue("스캔 서비스 연결 후 다시 시작하세요.", null, null);
            return;
        }
        model.starting = true;
        stopRequested = false;
        model.selected = null;
        model.selectedSession = null;
        model.scan = ScanService.Snapshot.idle();
        screen.issue(null, null, null);
        handler.removeCallbacks(startTimeout);
        handler.postDelayed(startTimeout, 6_000);
        long duration = ScanPreferences.DURATIONS[preferences.duration()];
        try {
            if (background) startForegroundService(ScanService.startIntent(this, preferences.mode(), duration));
            else service.startVisible(preferences.mode(), duration);
        } catch (SecurityException | IllegalStateException unavailable) {
            model.starting = false;
            handler.removeCallbacks(startTimeout);
            screen.issue("스캔을 시작할 수 없습니다. 권한과 기기 설정을 확인하세요.", "앱 권한 설정", this::appSettings);
        }
    }

    void clearResults() {
        if (service != null) service.clear();
    }

    void openObservation(ScanObservation row) { if (ensurePermissions()) screen.showObservation(row); }
    void findDevice(ScanObservation row) { if (ensurePermissions()) screen.showFinder(row); }
    boolean scanRunning() { return model.scan.state().active(); }
    SignalTracker.Reading signalReading(String key, String session) {
        return service == null ? null : service.signalReading(key, session);
    }
    List<ScanObservation> currentObservations() { return model.scan.observations(); }
    void registerTarget(ScanObservation row) { targets.fromObservation(row); }
    void openPhoto() { photos.choose(); }

    boolean showCandidateWarning(ScanObservation row) {
        if (!PermissionSetup.ready(this) || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                || isFinishing() || isDestroyed()) return false;
        warningRow = row;
        String message = getString(R.string.candidate_warning_body, row.name(), row.best().reason());
        if (candidateWarning != null && candidateWarning.isShowing()) {
            candidateWarning.setMessage(message);
            return true;
        }
        candidateWarning = new IconDialogBuilder(this)
                .setTitle(R.string.candidate_warning_title).setMessage(message)
                .setPositiveButton(R.string.confirm, null)
                .setNeutralButton(R.string.view_details, (dialog, which) -> {
                    select(R.id.nav_scan);
                    openObservation(warningRow);
                }).create();
        candidateWarning.show();
        return true;
    }

    private void configureOverlay(boolean enabled) {
        if (!enabled) {
            preferences.overlayAlerts(false);
            if (service != null) service.dismissOverlay();
        } else if (Settings.canDrawOverlays(this)) {
            preferences.overlayAlerts(true);
        } else {
            new IconDialogBuilder(this).setTitle(R.string.overlay_permission_title)
                    .setMessage(R.string.overlay_permission_body)
                    .setPositiveButton(R.string.open_settings, (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        try {
                            overlayPermission.launch(intent);
                        } catch (android.content.ActivityNotFoundException unavailable) {
                            screen.issue("이 기기에서는 다른 앱 위에 표시 설정을 열 수 없습니다.", null, null);
                        }
                    }).setNegativeButton(R.string.cancel, null).show();
        }
        renderSettings();
    }

    private boolean ensurePermissions() {
        if (permissionSetup == null || permissionSetup.refresh()) return true;
        screen.close();
        IconDialogBuilder.dismissAppDialogs(this);
        if (candidateWarning != null) {
            candidateWarning.dismiss();
            candidateWarning = null;
        }
        if (service != null && model.scan.state().active()) service.stopScan();
        return false;
    }

    private void bindSettings() {
        findViewById(R.id.candidate_sound_settings).setOnClickListener(
                view -> notificationSettings(ScanService.ALERT_CHANNEL));
        ((com.google.android.material.materialswitch.MaterialSwitch) findViewById(R.id.overlay_alerts))
                .setOnCheckedChangeListener((view, enabled) -> {
                    if (!renderingSettings) configureOverlay(enabled);
                });
        findViewById(R.id.default_mode).setOnClickListener(view -> chooseMode());
        findViewById(R.id.default_duration).setOnClickListener(view -> chooseDuration());
        findViewById(R.id.manage_targets).setOnClickListener(view -> targets.show());
        findViewById(R.id.photo).setOnClickListener(view -> openPhoto());
        findViewById(R.id.os_notifications).setOnClickListener(view -> notificationSettings(null));
        findViewById(R.id.theme).setOnClickListener(view -> chooseTheme());
        findViewById(R.id.retention).setOnClickListener(view -> history.chooseRetention(preferences));
        findViewById(R.id.required_permissions).setOnClickListener(view -> appSettings());
        findViewById(R.id.background_battery).setOnClickListener(view -> permissionSetup.openBatterySettings());
        com.google.android.material.materialswitch.MaterialSwitch alerts = findViewById(R.id.candidate_alerts);
        alerts.setOnCheckedChangeListener((view, enabled) -> {
            if (renderingSettings) return;
            preferences.alerts(enabled);
            if (!enabled && service != null) service.dismissOverlay();
            if (enabled && !ScanService.channelEnabled(this, ScanService.ALERT_CHANNEL)) {
                if (Build.VERSION.SDK_INT >= 33 && !preferences.askedNotifications()
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestNotificationPermission(false);
                } else notificationSettings(ScanService.ALERT_CHANNEL);
            }
            renderSettings();
        });
    }

    private void renderSettings() {
        renderingSettings = true;
        ((com.google.android.material.materialswitch.MaterialSwitch) findViewById(R.id.overlay_alerts))
                .setChecked(preferences.overlayAlerts() && Settings.canDrawOverlays(this));
        buttonText(R.id.default_mode, "기본 모드 · " + preferences.mode().label);
        buttonText(R.id.background_battery, getString(BackgroundSettings.batteryExempt(this)
                ? R.string.background_battery_complete : R.string.background_battery_needed));
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        findViewById(R.id.background_power_state).setVisibility(power != null && power.isPowerSaveMode() ? View.VISIBLE : View.GONE);
        buttonText(R.id.manage_targets, "탐색 대상 관리 · 기본 " + new SavedTargets(this).builtins().size() + "개 항목");
        buttonText(R.id.default_duration, "BLE / 통합 관측 시간 · " + ScanPreferences.DURATION_LABELS[preferences.duration()]);
        buttonText(R.id.retention, "이력 보관 · 최근 " + preferences.retention() + "회");
        buttonText(R.id.theme, "화면 테마 · " + themeNames()[Math.max(0, Math.min(2, preferences.theme()))]);
        findViewById(R.id.default_mode).setEnabled(!busy());
        findViewById(R.id.default_duration).setEnabled(!busy());
        com.google.android.material.materialswitch.MaterialSwitch alerts = findViewById(R.id.candidate_alerts);
        alerts.setChecked(preferences.alerts());
        NotificationManager manager = getSystemService(NotificationManager.class);
        TextView detail = findViewById(R.id.notification_settings_state);
        if (!manager.areNotificationsEnabled()) detail.setText("기기에서 앱 알림이 꺼져 있습니다.");
        else if (!ScanService.channelEnabled(this, ScanService.SCAN_CHANNEL)) detail.setText("스캔 진행 알림 채널이 차단되었습니다.");
        else if (preferences.alerts() && !ScanService.channelEnabled(this, ScanService.ALERT_CHANNEL)) {
            detail.setText("후보 탐지 알림 채널이 차단되었습니다.");
        } else detail.setText("스캔 진행 알림은 백그라운드 세션 동안 표시됩니다.");
        renderingSettings = false;
    }

    private void buttonText(int id, String title) { ((TextView) findViewById(id)).setText(title); }

    private void chooseTheme() {
        new IconDialogBuilder(this).setTitle("화면 테마")
                .setSingleChoiceItems(themeNames(), preferences.theme(), (dialog, value) -> {
                    preferences.theme(value);
                    dialog.dismiss();
                    getDelegate().setLocalNightMode(themeMode(value));
                }).setNegativeButton("취소", null).show();
    }

    private static String[] themeNames() { return new String[]{"시스템", "라이트", "다크"}; }
    private static int themeMode(int value) {
        return switch (value) {
            case 1 -> AppCompatDelegate.MODE_NIGHT_NO;
            case 2 -> AppCompatDelegate.MODE_NIGHT_YES;
            default -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        };
    }

    private void appSettings() {
        openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
    }

    private void notificationSettings(String channel) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        Intent intent;
        if (channel == null || !manager.areNotificationsEnabled()) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        } else {
            intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channel);
        }
        openSettings(intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            new IconDialogBuilder(this).setTitle(R.string.scan_setup_title).setMessage(R.string.scan_setup_unavailable)
                    .setPositiveButton(R.string.confirm, null).show();
        }
    }

    void preferencesChanged() { renderSettings(); }
}
