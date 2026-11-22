package com.uplusys.deviceguard;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PermissionPolicyTest {
    @Test public void notificationDenialKeepsSetupOpenEvenWithScanPermissions() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 33);
        Context context = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public int checkSelfPermission(String permission) {
                return Manifest.permission.POST_NOTIFICATIONS.equals(permission)
                        ? PackageManager.PERMISSION_DENIED : PackageManager.PERMISSION_GRANTED;
            }
        };
        assertFalse(PermissionSetup.ready(context));
        assertTrue(ScanPolicy.missingPermissions(context, RadioScanner.Mode.BLE).isEmpty());
        assertTrue(ScanPolicy.missingPermissions(context, RadioScanner.Mode.WIFI).isEmpty());
    }

    @Test public void classicPermissionAloneDoesNotCompleteSetup() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 31);
        Context context = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public int checkSelfPermission(String permission) {
                return Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)
                        || Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)
                        ? PackageManager.PERMISSION_DENIED : PackageManager.PERMISSION_GRANTED;
            }
        };
        assertTrue(ScanPolicy.missingPermissions(context, RadioScanner.Mode.CLASSIC).isEmpty());
        assertFalse(ScanPolicy.missingPermissions(context, RadioScanner.Mode.BLE).isEmpty());
        assertFalse(ScanPolicy.missingPermissions(context, RadioScanner.Mode.WIFI).isEmpty());
        assertFalse(PermissionSetup.ready(context));
    }

    @Test public void allFiveSetupItemsAreRequired() {
        assertFalse(new PermissionSetup.Status(false, true, true, true, true).ready());
        assertFalse(new PermissionSetup.Status(true, false, true, true, true).ready());
        assertFalse(new PermissionSetup.Status(true, true, false, true, true).ready());
        assertFalse(new PermissionSetup.Status(true, true, true, false, true).ready());
        assertFalse(new PermissionSetup.Status(true, true, true, true, false).ready());
        assertTrue(new PermissionSetup.Status(true, true, true, true, true).ready());
    }
}
