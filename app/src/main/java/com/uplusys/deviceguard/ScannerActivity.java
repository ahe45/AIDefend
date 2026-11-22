package com.uplusys.deviceguard;

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
import android.os.SystemClock;
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
import androidx.core.view.OneShotPreDrawListener;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationBarView;

import java.util.List;

public final class ScannerActivity extends AppCompatActivity {
    private static final long SPLASH_DURATION_MS = 2_000;
    private long splashDeadline = -1;
    private boolean splashVisible;
    private ScanObservation splashWarning;
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
                prepareScan();
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
    private DetectionSettings detectionSettings;
    private ScanViewModel model;
    private ScanScreen screen;
    private HistoryScreen history;
    private TargetsEditor targets;
    private PhotoPreview photos;
    private MaterialToolbar toolbar;
    private NavigationBarView navigation;
    record PageAction(int id, int label, int icon, Runnable run) {}
    private record SettingsPage(int route, CharSequence title, View content, Runnable onClose, PageAction[] actions) {}
    private final java.util.ArrayDeque<SettingsPage> settingsPages = new java.util.ArrayDeque<>();
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
        detectionSettings = new DetectionSettings(this);
        if (!preferences.advanced()) detectionSettings.automatic(true);
        getDelegate().setLocalNightMode(themeMode(preferences.theme()));
        setTheme(R.style.Theme_DeviceGuard);
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 31) getSplashScreen().setOnExitAnimationListener(android.window.SplashScreenView::remove);
        photos = new PhotoPreview(this, state);
        model = new ViewModelProvider(this).get(ScanViewModel.class);
        if (state != null) {
            model.tab = state.getInt("tab", R.id.nav_scan);
            model.candidatesOnly = state.getBoolean("candidates", true);
            model.selected = state.getString("selected");
            model.selectedSession = state.getString("selectedSession");
            notificationRequestedForScan = state.getBoolean("notifyScan");
        }
        setContentView(R.layout.activity_scanner);
        bindAppVersion();
        splashDeadline = state == null ? 0 : state.getLong("splashDeadline", -1);
        showSplash();
        ScanService.createChannels(this);
        installInsets();
        toolbar = findViewById(R.id.toolbar);
        navigation = findViewById(R.id.navigation);
        navigation.setItemActiveIndicatorEnabled(false);
        screen = new ScanScreen(this, model, preferences);
        if (state != null) screen.restoreScroll(state.getParcelable("list"));
        history = new HistoryScreen(this);
        targets = new TargetsEditor(this);
        navigation.setOnItemSelectedListener(item -> navigate(item.getItemId()));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (splashVisible) return;
                if (hasSettingsPage()) closeSettingsPage();
                else if (model.tab != R.id.nav_scan) select(R.id.nav_scan);
                else if (model.selected != null && screen.wide()) {
                    model.selected = null;
                    screen.render();
                } else if (busy()) {
                    new IconDialogBuilder(ScannerActivity.this)
                            .setTitle(R.string.scan_stop_confirmation)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.scan_stop, (dialog, which) -> {
                                if (busy()) requestScanStop();
                            }).show();
                } else {
                    new IconDialogBuilder(ScannerActivity.this)
                            .setTitle(R.string.exit_confirmation)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.exit_app, (dialog, which) -> finish())
                            .show();
                }
            }
        });
        bindSettings();
        permissionSetup = new PermissionSetup(this);
        select(model.tab);
        if (state != null) restoreSettingsPage(state);
        permissionSetup.refresh();
        if (state == null) acceptRoute(getIntent());
    }

    private void bindAppVersion() {
        String versionName = null;
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // The app can still launch if package metadata is unavailable.
        }
        TextView splashVersion = findViewById(R.id.splash_version);
        if (versionName == null || versionName.isBlank()) {
            findViewById(R.id.settings_version_row).setVisibility(View.GONE);
            splashVersion.setVisibility(View.GONE);
            return;
        }
        TextView settingsVersion = findViewById(R.id.settings_app_version);
        String displayVersion = getString(R.string.app_version_display, versionName);
        settingsVersion.setText(displayVersion);
        splashVersion.setText(displayVersion);
    }

    private void showSplash() {
        if (splashDeadline < 0 || (splashDeadline > 0 && splashDeadline <= SystemClock.elapsedRealtime())) {
            splashDeadline = -1;
            return;
        }
        splashVisible = true;
        View splash = findViewById(R.id.launch_splash);
        android.widget.ImageView artwork = findViewById(R.id.splash_artwork);
        artwork.setImageResource(R.drawable.splash_radar);
        splash.setVisibility(View.VISIBLE);
        findViewById(R.id.root).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        OneShotPreDrawListener.add(splash, () -> {
            if (splashDeadline == 0) splashDeadline = SystemClock.elapsedRealtime() + SPLASH_DURATION_MS;
            handler.postDelayed(this::finishSplash, Math.max(0, splashDeadline - SystemClock.elapsedRealtime()));
        });
    }

    boolean splashShowing() { return splashVisible; }

    private void finishSplash() {
        if (!splashVisible) return;
        splashVisible = false;
        splashDeadline = -1;
        findViewById(R.id.launch_splash).setVisibility(View.GONE);
        android.widget.ImageView artwork = findViewById(R.id.splash_artwork);
        artwork.setImageDrawable(null);
        findViewById(R.id.root).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (ensurePermissions() && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            handleRoute();
            screen.restoreObservation();
            photos.restore();
            if (splashWarning != null) {
                showCandidateWarning(splashWarning);
                splashWarning = null;
            }
        }
    }

    private void installInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        boolean dark = splashVisible || (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(!dark);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(!dark);
        View root = findViewById(R.id.root);
        View frame = findViewById(R.id.scanner_frame);
        View bottomNavigation = findViewById(R.id.navigation);
        View splashVersion = findViewById(R.id.splash_version);
        int spacing = getResources().getDimensionPixelSize(R.dimen.space_medium);
        ViewCompat.setOnApplyWindowInsetsListener(frame, (view, insets) -> {
            int systemEdges = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
            Insets safe = insets.getInsets(systemEdges | WindowInsetsCompat.Type.ime());
            int gestureBottom = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom;
            boolean menuUsesGestureArea = bottomNavigation.getVisibility() == View.VISIBLE
                    && Build.VERSION.SDK_INT >= 29
                    && insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom == 0;
            int bottom = menuUsesGestureArea
                    ? insets.getInsets((systemEdges & ~WindowInsetsCompat.Type.navigationBars())
                            | WindowInsetsCompat.Type.ime()).bottom
                    : Math.max(safe.bottom, gestureBottom);
            root.setPadding(safe.left, safe.top, safe.right, bottom);
            Insets splashSafe = insets.getInsets(systemEdges);
            var versionLayout = (android.widget.FrameLayout.LayoutParams) splashVersion.getLayoutParams();
            versionLayout.leftMargin = splashSafe.left + spacing;
            versionLayout.rightMargin = splashSafe.right + spacing;
            versionLayout.bottomMargin = splashSafe.bottom + spacing;
            splashVersion.setLayoutParams(versionLayout);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(frame);
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
        if (splashVisible) return;
        handleRoute();
        if (model.tab == R.id.nav_history) history.refresh();
        photos.restore();
        if (splashWarning != null) {
            showCandidateWarning(splashWarning);
            splashWarning = null;
        }
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
        SettingsPage rootPage = settingsPages.peekFirst();
        if (rootPage != null) {
            state.putInt("settingsPage", rootPage.route());
            state.putInt("currentSettingsPage", settingsPages.peekLast().route());
            state.putBundle("targetsPage", targets.savePageState());
            if (rootPage.route() == R.id.history_session_results) {
                state.putString("historySessionId", history.openId());
                state.putBoolean("historyCandidatesOnly", history.candidatesOnly());
                state.putInt("historyDevicePosition", history.selectedResultPosition());
            }
        }
        state.putInt("tab", model.tab);
        state.putBoolean("candidates", model.candidatesOnly);
        state.putString("selected", model.selected);
        state.putString("selectedSession", model.selectedSession);
        state.putBoolean("notifyScan", notificationRequestedForScan);
        state.putLong("splashDeadline", splashDeadline);
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
        if (!splashVisible && model.tab == R.id.nav_scan && PermissionSetup.ready(this)) screen.restoreObservation();
    }

    private void acceptRoute(Intent intent) {
        model.routeSession = intent.getStringExtra(ScanService.EXTRA_SESSION);
        model.routeObservation = intent.getStringExtra(ScanService.EXTRA_OBSERVATION);
        model.routeFind = intent.getBooleanExtra(ScanService.EXTRA_FIND, false);
    }

    private void handleRoute() {
        if (splashVisible || !PermissionSetup.ready(this) || model.routeSession == null) return;
        String id = model.routeSession;
        String observation = model.routeObservation;
        model.routeSession = null;
        model.routeObservation = null;
        if (id.equals(model.scan.id()) && model.scan.state().active()) {
            select(R.id.nav_scan);
            ScanObservation row = screen.find(observation);
            if (row != null) {
                if (model.routeFind) findDevice(row);
                else openObservation(row);
                model.routeFind = false;
            }
            else if (observation != null) {
                model.routeSession = id;
                model.routeObservation = observation;
            }
        } else {
            model.routeFind = false;
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
        while (!settingsPages.isEmpty()) settingsPages.removeLast().onClose().run();
        renderSettingsPage();
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

    boolean hasSettingsPage() { return !settingsPages.isEmpty(); }

    void showSettingsPage(int route, CharSequence title, View content, Runnable onClose, PageAction... actions) {
        clearSettingsFocus();
        if (!hasSettingsPage()) screen.close();
        settingsPages.addLast(new SettingsPage(route, title, content, onClose, actions));
        renderSettingsPage();
        if (permissionSetup != null) permissionSetup.refresh();
    }

    void closeSettingsPage() {
        if (!hasSettingsPage()) return;
        clearSettingsFocus();
        settingsPages.removeLast().onClose().run();
        if (hasSettingsPage()) renderSettingsPage();
        else navigate(model.tab);
    }

    void closeSettingsPage(View expected) {
        SettingsPage current = settingsPages.peekLast();
        if (current != null && current.content() == expected) closeSettingsPage();
    }

    private void clearSettingsFocus() {
        View focused = getCurrentFocus();
        if (focused instanceof android.widget.EditText) {
            var keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
    }

    private void renderSettingsPage() {
        android.widget.FrameLayout host = findViewById(R.id.settings_page);
        SettingsPage current = settingsPages.peekLast();
        toolbar.getMenu().clear();
        host.removeAllViews();
        host.setVisibility(current == null ? View.GONE : View.VISIBLE);
        if (current == null) {
            toolbar.setNavigationIcon(null);
            toolbar.setNavigationOnClickListener(null);
            return;
        }
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationIconTint(getColor(R.color.on_surface));
        toolbar.setNavigationContentDescription(R.string.settings_back);
        toolbar.setNavigationOnClickListener(view -> closeSettingsPage());
        toolbar.setTitle(current.title());
        for (PageAction action : current.actions()) {
            var item = toolbar.getMenu().add(android.view.Menu.NONE, action.id(), android.view.Menu.NONE, action.label());
            item.setIcon(action.icon());
            item.setIconTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.on_surface)));
            item.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
            item.setOnMenuItemClickListener(clicked -> {
                action.run().run();
                return true;
            });
        }
        for (int id : new int[]{R.id.scan_screen, R.id.history_screen, R.id.settings_screen}) {
            findViewById(id).setVisibility(View.GONE);
        }
        host.addView(current.content(), new android.widget.FrameLayout.LayoutParams(-1, -1));
        renderSettings();
    }

    private void restoreSettingsPage(Bundle state) {
        int route = state.getInt("settingsPage");
        if (route == R.id.categories_setting) {
            chooseCategories();
        } else if (route == R.id.history_session_results) {
            String id = state.getString("historySessionId");
            if (id != null) history.open(id, state.getBoolean("historyCandidatesOnly", true),
                    state.getInt("historyDevicePosition", -1));
        } else if (route == R.id.advanced_scan_settings) {
            showAdvancedScanSettings();
            if (state.getInt("currentSettingsPage") == R.id.default_mode) chooseMode();
        } else if (route == R.id.candidate_alert_settings) showAlertSettings(false);
        else if (route == R.id.overlay_alert_settings) showAlertSettings(true);
        else if (route == R.id.default_mode) chooseMode();
        else if (route == R.id.default_duration) chooseDuration();
        else if (route == R.id.manage_targets || route == R.id.target_device_category) {
            targets.restorePageState(state.getBundle("targetsPage"), route == R.id.manage_targets);
        }
        else if (route == R.id.theme) chooseTheme();
        else if (route == R.id.retention) history.chooseRetention(preferences);
    }

    void showSettingOptions(int route, String title, String[] labels, int selected,
                            java.util.function.IntConsumer onChoice) {
        android.widget.ListView list = new android.widget.ListView(this);
        int inset = getResources().getDimensionPixelSize(R.dimen.space_medium);
        list.setPadding(inset, inset, inset, inset);
        list.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, labels));
        list.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        list.setItemChecked(selected, true);
        list.setOnItemClickListener((parent, view, index, id) -> onChoice.accept(index));
        showSettingsPage(route, title, list, () -> {});
    }

    void restoreSettingOption(int index) {
        SettingsPage current = settingsPages.peekLast();
        if (current != null && current.route() == R.id.retention && current.content() instanceof android.widget.ListView list) {
            list.setItemChecked(index, true);
        }
    }

    com.google.android.material.button.MaterialButton settingsAction(int id, CharSequence text, int icon, boolean primary) {
        var button = new com.google.android.material.button.MaterialButton(this, null, primary
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setId(id);
        button.setText(text);
        button.setIconResource(icon);
        return button;
    }

    View settingsWithActions(View content, com.google.android.material.button.MaterialButton... buttons) {
        var page = new android.widget.LinearLayout(this);
        page.setOrientation(android.widget.LinearLayout.VERTICAL);
        page.addView(content, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
        var actions = new android.widget.LinearLayout(this);
        int inset = getResources().getDimensionPixelSize(R.dimen.space_medium);
        actions.setPadding(inset, inset / 2, inset, inset);
        for (int i = 0; i < buttons.length; i++) {
            var params = new android.widget.LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) params.setMarginStart(inset / 2);
            actions.addView(buttons[i], params);
        }
        page.addView(actions, new android.widget.LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    void chooseMode() {
        RadioScanner.Mode[] modes = RadioScanner.Mode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < labels.length; i++) labels[i] = modes[i].label;
        showSettingOptions(R.id.default_mode, "스캔 모드", labels, preferences.mode().ordinal(), index -> {
                    preferences.mode(modes[index]);
                    preferences.advanced(true);
                    detectionSettings.automatic(false);
                    screen.issue(null, null, null);
                    renderSettings();
                    closeSettingsPage();
                });
    }

    void chooseDuration() {
        showSettingOptions(R.id.default_duration, "BLE / 통합 스캔 시간", ScanPreferences.DURATION_LABELS,
                preferences.duration(), index -> {
                    preferences.duration(index);
                    screen.render();
                    renderSettings();
                    closeSettingsPage();
                });
    }

    private boolean busy() { return model.starting || model.scan.state().active(); }

    int selectedCategoryCount() { return detectionSettings.selected().size(); }

    SessionConfig configuredSession() {
        return detectionSettings.snapshot(new SavedTargets(this).snapshot(), preferences.mode());
    }

    View chooseCategories() {
        android.widget.LinearLayout body = new android.widget.LinearLayout(this);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        int inset = getResources().getDimensionPixelSize(R.dimen.space_medium);
        body.setPadding(inset, inset, inset, inset);
        if (busy()) body.addView(ScanScreen.label(this, "변경 사항은 다음 스캔부터 적용됩니다."));
        var selected = detectionSettings.selected();
        boolean hasPrevious = false;
        for (DeviceCategory category : DeviceCategory.values()) {
            if (!category.selectable()) continue;
            if (hasPrevious) {
                body.addView(new com.google.android.material.divider.MaterialDivider(this),
                        new android.widget.LinearLayout.LayoutParams(-1, -2));
            }
            hasPrevious = true;
            var toggle = new com.google.android.material.materialswitch.MaterialSwitch(this);
            toggle.setTag(category);
            toggle.setText(category.label);
            toggle.setTextSize(16);
            toggle.setMinHeight(getResources().getDimensionPixelSize(R.dimen.touch_min));
            toggle.setPaddingRelative(0, inset / 2, 0, inset / 2);
            toggle.setChecked(selected.contains(category));
            toggle.setOnCheckedChangeListener((button, checked) -> changeScanCategory(category, button, checked));
            body.addView(toggle, new android.widget.LinearLayout.LayoutParams(-1, -2));
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);
        showSettingsPage(R.id.categories_setting, getString(R.string.detection_categories_title), scroll, () -> {});
        return scroll;
    }

    private void changeScanCategory(DeviceCategory category, android.widget.CompoundButton button, boolean checked) {
        var saved = detectionSettings.selected();
        if (saved.contains(category) == checked) return;
        var updated = java.util.EnumSet.noneOf(DeviceCategory.class);
        updated.addAll(saved);
        if (checked) updated.add(category);
        else updated.remove(category);
        if (updated.isEmpty()) {
            button.setChecked(true);
            new IconDialogBuilder(this).setTitle(R.string.detection_categories_required_title)
                    .setMessage(R.string.detection_categories_required)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        detectionSettings.apply(updated);
        screen.render();
    }

    void scanAction() {
        if (!ensurePermissions()) return;
        if (busy()) {
            requestScanStop();
            return;
        }
        screen.issue(null, null, null);
        prepareScan();
    }

    private void requestScanStop() {
        if (model.scan.state() == ScanService.State.RUNNING && service != null) service.stopScan();
        else stopRequested = true;
    }

    private void prepareScan() {
        if (detectionSettings.selected().isEmpty()) {
            screen.issue("스캔할 기기를 한 종류 이상 선택하세요", "스캔할 기기", this::chooseCategories);
            chooseCategories();
            return;
        }
        SessionConfig config = configuredSession();
        try {
            List<ScanPolicy.Problem> problems = ScanPolicy.startupProblems(this, config);
            if (!problems.isEmpty()) {
                showScanSetup(problems);
                return;
            }
        } catch (SecurityException changed) {
            screen.issue("스캔 권한이 변경되었습니다.", "앱 권한 설정", this::appSettings);
            return;
        }
        confirmModeCoverage();
    }

    private void confirmModeCoverage() {
        SessionConfig config = configuredSession();
        if (!config.automatic()) {
            SessionConfig automatic = SessionConfig.create(config.categories(), config.rules(), config.mode(), true);
            List<RadioScanner.Mode> missing = automatic.paths().stream().filter(path -> !config.paths().contains(path))
                    .collect(java.util.stream.Collectors.toList());
            if (!missing.isEmpty()) {
                new IconDialogBuilder(this).setTitle("수동 스캔 범위 확인")
                        .setMessage("현재 경로  " + config.summary() + "\n\n선택 유형의 추가 경로  "
                                + missing.stream().map(path -> path.label).collect(java.util.stream.Collectors.joining(" · "))
                                + "\n현재 수동 모드로는 위 경로를 스캔하지 않습니다.")
                        .setPositiveButton("현재 경로로 시작", (dialog, which) -> startPrepared())
                        .setNeutralButton("자동 스캔으로 시작", (dialog, which) -> {
                            detectionSettings.automatic(true);
                            renderSettings();
                            prepareScan();
                        }).setNegativeButton("취소", null).show();
                return;
            }
        }
        startPrepared();
    }

    private void startPrepared() {
        if (ScanService.channelEnabled(this, ScanService.SCAN_CHANNEL)) {
            start(true);
        } else if (Build.VERSION.SDK_INT >= 33 && !preferences.askedNotifications()
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            new IconDialogBuilder(this).setTitle("백그라운드 스캔 알림")
                    .setMessage("화면이 잠겨 있을 때도 진행 상태와 새 의심 기기를 보려면 알림을 허용하세요.")
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
            } else if (Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(problem.action())) {
                title = R.string.permission_app_settings;
                icon = R.drawable.ic_permission_settings;
            } else {
                title = R.string.scan_setup_location;
                icon = R.drawable.ic_permission_location;
            }
            button.setText(title);
            button.setIconResource(icon);
            button.setOnClickListener(view -> {
                if (Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(problem.action())) appSettings();
                else openSettings(new Intent(problem.action()));
            });
            actions.addView(button, new android.widget.LinearLayout.LayoutParams(-1, -2));
        }
        SessionConfig config = configuredSession();
        explanation.setText(getString(R.string.scan_setup_message, config.summary(), String.join("\n", messages)));
        List<String> missingPermissions = ScanPolicy.missingPermissions(this, config);
        com.google.android.material.button.MaterialButton request = null;
        if (!missingPermissions.isEmpty()) {
            request = new com.google.android.material.button.MaterialButton(this);
            request.setText("권한 요청");
            request.setIconResource(R.drawable.ic_permission_settings);
            actions.addView(request);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(actions);
        MaterialAlertDialogBuilder builder = new IconDialogBuilder(this).setTitle(R.string.scan_setup_title)
                .setView(scroll).setPositiveButton(R.string.scan_setup_retry, null).setNegativeButton(R.string.cancel, null);
        if (!ScanPolicy.executablePaths(this, config).isEmpty()) {
            builder.setNeutralButton("가능한 경로로 시작", (dialog, which) -> confirmModeCoverage());
        } else if (needsModeChoice) builder.setNeutralButton(R.string.scan_setup_mode, (dialog, which) -> chooseMode());
        androidx.appcompat.app.AlertDialog dialog = builder.show();
        if (request != null) request.setOnClickListener(view -> {
            dialog.dismiss();
            scanPermissions.launch(missingPermissions.toArray(new String[0]));
        });
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
            RadioScanner.Mode mode = configuredSession().mode();
            if (background) startForegroundService(ScanService.startIntent(this, mode, duration));
            else service.startVisible(mode, duration);
        } catch (SecurityException | IllegalStateException unavailable) {
            model.starting = false;
            handler.removeCallbacks(startTimeout);
            screen.issue("스캔을 시작할 수 없습니다. 권한과 기기 설정을 확인하세요.", "앱 권한 설정", this::appSettings);
        }
    }

    void openObservation(ScanObservation row) { if (ensurePermissions()) screen.showObservation(row); }
    void findDevice(ScanObservation row) { if (ensurePermissions()) screen.showFinder(row); }
    boolean scanRunning() { return model.scan.state().active(); }
    SignalTracker.Reading signalReading(String key, String session) {
        return service == null ? null : service.signalReading(key, session);
    }
    List<ScanObservation> currentObservations() { return model.scan.observations(); }
    void registerTarget(ScanObservation row) { targets.fromObservation(row); }

    boolean showCandidateWarning(ScanObservation row) {
        if (!PermissionSetup.ready(this) || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                || isFinishing() || isDestroyed()) return false;
        if (splashVisible) {
            splashWarning = row;
            return true;
        }
        warningRow = row;
        if (candidateWarning != null && candidateWarning.isShowing()) {
            CandidateWarningContent.bind(candidateWarning.findViewById(R.id.candidate_warning_content), row);
            return true;
        }
        View content = CandidateWarningContent.create(this, row, () -> {
            candidateWarning.dismiss();
            select(R.id.nav_scan);
            openObservation(warningRow);
        });
        candidateWarning = new IconDialogBuilder(this).setView(content).create();
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
        findViewById(R.id.categories_setting).setOnClickListener(view -> chooseCategories());
        findViewById(R.id.advanced_scan_settings).setOnClickListener(view -> showAdvancedScanSettings());
        findViewById(R.id.candidate_alert_settings).setOnClickListener(view -> showAlertSettings(false));
        findViewById(R.id.overlay_alert_settings).setOnClickListener(view -> showAlertSettings(true));
        findViewById(R.id.candidate_sound_settings).setOnClickListener(
                view -> notificationSettings(ScanService.ALERT_CHANNEL));
        findViewById(R.id.default_duration).setOnClickListener(view -> chooseDuration());
        findViewById(R.id.manage_targets).setOnClickListener(view -> targets.show());
        findViewById(R.id.os_notifications).setOnClickListener(view -> notificationSettings(null));
        findViewById(R.id.theme).setOnClickListener(view -> chooseTheme());
        findViewById(R.id.retention).setOnClickListener(view -> history.chooseRetention(preferences));
        findViewById(R.id.required_permissions).setOnClickListener(view -> appSettings());
        findViewById(R.id.background_battery).setOnClickListener(view -> permissionSetup.openBatterySettings());
    }

    private void showAdvancedScanSettings() {
        View page = getLayoutInflater().inflate(R.layout.settings_advanced_scan, null);
        android.widget.CompoundButton advanced = page.findViewById(R.id.advanced_scanning);
        android.widget.CompoundButton automatic = page.findViewById(R.id.automatic_scanning);
        advanced.setSaveEnabled(false);
        automatic.setSaveEnabled(false);
        advanced.setOnCheckedChangeListener((view, checked) -> {
            if (renderingSettings) return;
            preferences.advanced(checked);
            if (!checked) detectionSettings.automatic(true);
            preferencesChanged();
        });
        automatic.setOnCheckedChangeListener((view, checked) -> {
            if (renderingSettings) return;
            detectionSettings.automatic(checked);
            preferencesChanged();
        });
        page.findViewById(R.id.default_mode).setOnClickListener(view -> chooseMode());
        showScrollableSettingsPage(R.id.advanced_scan_settings, R.string.settings_advanced_scan, page);
    }

    private void showAlertSettings(boolean overlay) {
        View page = getLayoutInflater().inflate(R.layout.settings_alert_toggle, null);
        int label = overlay ? R.string.overlay_alert_setting : R.string.candidate_alerts_setting;
        android.widget.CompoundButton toggle = page.findViewById(R.id.settings_alert_toggle);
        toggle.setText(label);
        toggle.setSaveEnabled(false);
        toggle.setOnCheckedChangeListener((view, enabled) -> {
            if (renderingSettings) return;
            if (overlay) configureOverlay(enabled);
            else configureCandidateAlerts(enabled);
        });
        int route = overlay ? R.id.overlay_alert_settings : R.id.candidate_alert_settings;
        showScrollableSettingsPage(route, label, page);
    }

    private void showScrollableSettingsPage(int route, int title, View content) {
        var scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        showSettingsPage(route, getString(title), scroll, () -> {});
    }

    private void configureCandidateAlerts(boolean enabled) {
        preferences.alerts(enabled);
        if (!enabled && service != null) service.dismissOverlay();
        if (enabled && !ScanService.channelEnabled(this, ScanService.ALERT_CHANNEL)) {
            if (Build.VERSION.SDK_INT >= 33 && !preferences.askedNotifications()
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission(false);
            } else notificationSettings(ScanService.ALERT_CHANNEL);
        }
        renderSettings();
    }

    private void renderSettings() {
        SettingsPage page = settingsPages.peekLast();
        if (page == null) return;
        renderingSettings = true;
        if (page.route() == R.id.advanced_scan_settings) {
            android.widget.CompoundButton advanced = page.content().findViewById(R.id.advanced_scanning);
            android.widget.CompoundButton automatic = page.content().findViewById(R.id.automatic_scanning);
            View manual = page.content().findViewById(R.id.default_mode);
            advanced.setChecked(preferences.advanced());
            automatic.setChecked(detectionSettings.automatic());
            int visibility = preferences.advanced() ? View.VISIBLE : View.GONE;
            automatic.setVisibility(visibility);
            manual.setVisibility(visibility);
            manual.setEnabled(!detectionSettings.automatic());
        } else if (page.route() == R.id.candidate_alert_settings || page.route() == R.id.overlay_alert_settings) {
            android.widget.CompoundButton toggle = page.content().findViewById(R.id.settings_alert_toggle);
            toggle.setChecked(page.route() == R.id.candidate_alert_settings ? preferences.alerts()
                    : preferences.overlayAlerts() && Settings.canDrawOverlays(this));
        }
        renderingSettings = false;
    }

    private void chooseTheme() {
        showSettingOptions(R.id.theme, "화면 테마", themeNames(), preferences.theme(), value -> {
                    preferences.theme(value);
                    closeSettingsPage();
                    getDelegate().setLocalNightMode(themeMode(value));
                });
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

    void preferencesChanged() { renderSettings(); screen.render(); }
}
