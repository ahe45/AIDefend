package com.uplusys.deviceguard;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import com.google.android.material.button.MaterialButton;
import java.util.Arrays;
import java.util.List;

/** Query required permission and battery exemption states on every refresh. */
final class PermissionSetup {
    record Status(boolean location, boolean nearby, boolean notifications, boolean overlay, boolean battery) {
        boolean ready() {
            return location && nearby && notifications && overlay && battery;
        }
    }

    private final ScannerActivity activity;
    private final SharedPreferences history;
    private final ActivityResultLauncher<String[]> locationPermission;
    private final ActivityResultLauncher<String[]> nearbyPermissions;
    private final ActivityResultLauncher<String> notifications;
    private final ActivityResultLauncher<Intent> overlaySettings;
    private final ActivityResultLauncher<Intent> settings;

    PermissionSetup(ScannerActivity activity) {
        this.activity = activity;
        history = activity.getSharedPreferences("permission_setup", Context.MODE_PRIVATE);
        locationPermission = activity.registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            refresh();
            if (locationReady(activity)) advance();
        });
        nearbyPermissions = activity.registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            refresh();
            if (nearbyReady(activity)) advance();
        });
        notifications = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            refresh();
            if (granted) advance();
        });
        settings = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> refresh());
        overlaySettings = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            refresh();
            if (Settings.canDrawOverlays(activity)) advance();
        });
        activity.findViewById(R.id.permission_continue).setOnClickListener(view -> advance());
        activity.findViewById(R.id.permission_app_settings).setOnClickListener(view -> appSettings());
        configureCard(R.id.permission_location_card, R.string.permission_location,
                R.drawable.ic_permission_location, this::requestLocation);
        configureCard(R.id.permission_nearby_card, R.string.permission_nearby,
                R.drawable.ic_permission_bluetooth, this::requestNearby);
        configureCard(R.id.permission_notifications_card, R.string.permission_notifications,
                R.drawable.ic_permission_bell, this::requestNotifications);
        configureCard(R.id.permission_overlay_card, R.string.permission_overlay,
                R.drawable.ic_permission_overlay, this::requestOverlay);
        configureCard(R.id.permission_background_card, R.string.background_title,
                R.drawable.ic_permission_battery, this::openBatterySettings);
        View screen = activity.findViewById(R.id.permission_screen);
        View content = activity.findViewById(R.id.permission_content);
        screen.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left, dp(760));
            if (content.getLayoutParams().width != width) {
                android.view.ViewGroup.LayoutParams params = content.getLayoutParams();
                params.width = width;
                content.setLayoutParams(params);
            }
        });
    }

    static boolean notificationsReady(Context context) {
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
                && ScanService.channelEnabled(context, ScanService.SCAN_CHANNEL)
                && ScanService.channelEnabled(context, ScanService.ALERT_CHANNEL);
    }

    static boolean ready(Context context) {
        return status(context).ready();
    }

    private static Status status(Context context) {
        return new Status(locationReady(context), nearbyReady(context), notificationsReady(context),
                Settings.canDrawOverlays(context), BackgroundSettings.batteryExempt(context));
    }

    boolean refresh() {
        Status status = status(activity);
        boolean location = status.location();
        boolean nearby = status.nearby();
        boolean notifications = status.notifications();
        boolean overlay = status.overlay();
        boolean battery = status.battery();
        boolean ready = status.ready();
        View setup = activity.findViewById(R.id.permission_screen);
        boolean enteringSetup = !ready && setup.getVisibility() != View.VISIBLE;
        setup.setVisibility(ready ? View.GONE : View.VISIBLE);
        activity.findViewById(R.id.app_content).setVisibility(ready ? View.VISIBLE : View.GONE);
        View navigation = activity.findViewById(R.id.navigation);
        int visibility = ready && !activity.hasSettingsPage() ? View.VISIBLE : View.GONE;
        if (navigation.getVisibility() != visibility) {
            navigation.setVisibility(visibility);
            ViewCompat.requestApplyInsets(activity.findViewById(R.id.scanner_frame));
        }
        activity.findViewById(R.id.toolbar).setVisibility(ready ? View.VISIBLE : View.GONE);
        int background = activity.getColor(ready ? R.color.surface : R.color.permission_background);
        activity.findViewById(R.id.root).setBackgroundColor(background);
        boolean lightBars = !activity.splashShowing() && ColorUtils.calculateLuminance(background) > 0.5;
        WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView())
                .setAppearanceLightStatusBars(lightBars);
        WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView())
                .setAppearanceLightNavigationBars(lightBars);
        renderCard(R.id.permission_location_card, location);
        renderCard(R.id.permission_nearby_card, nearby);
        renderCard(R.id.permission_notifications_card, notifications);
        renderCard(R.id.permission_overlay_card, overlay);
        renderCard(R.id.permission_background_card, battery);
        int completed = (location ? 1 : 0) + (nearby ? 1 : 0) + (notifications ? 1 : 0) + (overlay ? 1 : 0) + (battery ? 1 : 0);
        int total = 5;
        TextView progress = activity.findViewById(R.id.permission_state);
        String text = activity.getString(R.string.permission_progress, completed, total);
        boolean progressChanged = !text.contentEquals(progress.getText());
        if (progressChanged) {
            progress.setText(text);
            int[] segments = {R.id.permission_segment_location, R.id.permission_segment_nearby,
                    R.id.permission_segment_notifications, R.id.permission_segment_overlay, R.id.permission_segment_background};
            for (int i = 0; i < total; i++) {
                activity.findViewById(segments[i]).setBackground(rounded(activity.getColor(i < completed
                        ? R.color.permission_green : R.color.permission_track), 100));
            }
        }
        if (!ready && (enteringSetup || progressChanged)) {
            int pending = !location ? R.id.permission_location_card : !nearby ? R.id.permission_nearby_card
                    : !notifications ? R.id.permission_notifications_card : !overlay ? R.id.permission_overlay_card
                    : R.id.permission_background_card;
            setup.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    setup.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    View card = activity.findViewById(pending);
                    card.requestRectangleOnScreen(new android.graphics.Rect(0, 0, card.getWidth(), card.getHeight()), true);
                }
            });
        }
        return ready;
    }

    private void configureCard(int id, int title, int icon, Runnable action) {
        View card = activity.findViewById(id);
        ((TextView) card.findViewById(R.id.permission_card_title)).setText(title);
        ((ImageView) card.findViewById(R.id.permission_icon)).setImageResource(icon);
        MaterialButton button = card.findViewById(R.id.permission_card_action);
        button.setContentDescription(activity.getString(R.string.permission_setup_action, activity.getString(title)));
        button.setOnClickListener(view -> action.run());
    }

    private void renderCard(int id, boolean complete) {
        View card = activity.findViewById(id);
        int status = complete ? R.string.permission_complete : R.string.permission_needed;
        // Re-query grants on every refresh, then update visuals only when their state changes.
        if (Integer.valueOf(status).equals(card.getTag())) return;
        card.setTag(status);
        ImageView icon = card.findViewById(R.id.permission_icon);
        int color = activity.getColor(complete ? R.color.permission_green : R.color.permission_needed_text);
        icon.setImageTintList(ColorStateList.valueOf(activity.getColor(complete
                ? R.color.permission_green : R.color.permission_pending_icon)));
        icon.setBackground(rounded(activity.getColor(complete
                ? R.color.permission_complete_background : R.color.permission_pending_background), 14));
        TextView badge = card.findViewById(R.id.permission_badge);
        badge.setText(status);
        badge.setTextColor(complete ? activity.getColor(R.color.permission_card) : color);
        badge.setBackground(rounded(activity.getColor(complete
                ? R.color.permission_green : R.color.permission_needed_background), 100));
        Drawable mark = AppCompatResources.getDrawable(activity, complete
                ? R.drawable.ic_permission_check : R.drawable.ic_permission_attention).mutate();
        mark.setTint(complete ? activity.getColor(R.color.permission_card) : color);
        mark.setBounds(0, 0, dp(17), dp(17));
        badge.setCompoundDrawablesRelative(mark, null, null, null);
        card.findViewById(R.id.permission_card_action).setVisibility(complete ? View.GONE : View.VISIBLE);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static boolean locationReady(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean nearbyReady(Context context) {
        return Build.VERSION.SDK_INT < 31
                || (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED);
    }

    private void advance() {
        Status status = status(activity);
        if (!status.location()) requestLocation();
        else if (!status.nearby()) requestNearby();
        else if (!status.notifications()) requestNotifications();
        else if (!status.overlay()) requestOverlay();
        else if (!status.battery()) openBatterySettings();
        else refresh();
    }

    void openBatterySettings() { openBackground(BackgroundSettings.batteryIntent(activity)); }

    private void openBackground(Intent intent) {
        try {
            settings.launch(intent);
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            new IconDialogBuilder(activity).duringSetup().setTitle(R.string.background_title)
                    .setMessage(R.string.background_settings_fallback)
                    .setPositiveButton(R.string.permission_app_settings, (dialog, which) -> appSettings())
                    .setNegativeButton(R.string.cancel, null).show();
        }
    }

    private void requestLocation() {
        requestRuntimeGroup(Arrays.asList(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), "location_requested", locationPermission);
    }

    private void requestNearby() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestRuntimeGroup(Arrays.asList(Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT), "nearby_requested", nearbyPermissions);
        } else refresh();
    }

    private void requestRuntimeGroup(List<String> permissions, String requestKey, ActivityResultLauncher<String[]> launcher) {
        boolean missing = false;
        boolean rationale = false;
        for (String permission : permissions) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing = true;
                rationale |= activity.shouldShowRequestPermissionRationale(permission);
            }
        }
        if (!missing) {
            refresh();
            return;
        }
        if (history.getBoolean(requestKey, false) && !rationale) {
            appSettings();
        } else if (rationale) {
            new IconDialogBuilder(activity).duringSetup().setTitle("검색 권한이 필요합니다")
                    .setMessage("선택한 권한을 허용해야 주변 무선 신호를 검색할 수 있습니다.")
                    .setPositiveButton("권한 요청", (dialog, which) -> requestRuntime(permissions, requestKey, launcher))
                    .setNegativeButton("취소", null).show();
        } else requestRuntime(permissions, requestKey, launcher);
    }

    private void requestNotifications() {
        if (notificationsReady(activity)) {
            refresh();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && (!history.getBoolean("notifications_requested", false)
                || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            new IconDialogBuilder(activity).duringSetup().setTitle("알림 권한이 필요합니다")
                    .setMessage("스캔 진행 상태와 의심 기기 경고를 표시합니다.")
                    .setPositiveButton("알림 허용", (dialog, which) -> {
                        history.edit().putBoolean("notifications_requested", true).apply();
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS);
                    }).setNegativeButton("취소", null).show();
        } else {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            if (activity.getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) {
                intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, ScanService.channelEnabled(activity, ScanService.SCAN_CHANNEL)
                                ? ScanService.ALERT_CHANNEL : ScanService.SCAN_CHANNEL);
            }
            open(intent);
        }
    }

    private void requestOverlay() {
        if (Settings.canDrawOverlays(activity)) {
            refresh();
            return;
        }
        new IconDialogBuilder(activity).duringSetup().setTitle("다른 앱 위에 표시 권한이 필요합니다")
                .setMessage("다른 앱을 사용하는 중에도 스캔 경고를 보여줍니다. 설정 화면에서 이 앱의 ‘다른 앱 위에 표시’를 허용한 뒤 돌아오세요.")
                .setPositiveButton("설정 열기", (dialog, which) -> open(new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.getPackageName())), overlaySettings))
                .setNegativeButton("취소", null).show();
    }

    private void requestRuntime(List<String> permissions, String requestKey, ActivityResultLauncher<String[]> launcher) {
        history.edit().putBoolean(requestKey, true).apply();
        launcher.launch(permissions.toArray(new String[0]));
    }

    private void appSettings() {
        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName())));
    }

    private void open(Intent intent) {
        open(intent, settings);
    }

    private void open(Intent intent, ActivityResultLauncher<Intent> launcher) {
        try {
            launcher.launch(intent);
        } catch (android.content.ActivityNotFoundException unavailable) {
            new IconDialogBuilder(activity).duringSetup().setMessage("이 기기에서는 해당 설정을 열 수 없습니다. Android 앱 설정에서 권한을 확인해 주세요.")
                    .setPositiveButton("확인", null).show();
        }
    }
}
