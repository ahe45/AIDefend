package com.uplusys.deviceguard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in upgrade probe. Reads user records and writes only an isolated baseline preference. */
@RunWith(AndroidJUnit4.class)
public class UpgradeCompatibilityTest {
    @Test public void captureBeforeUpgrade() throws Exception {
        org.junit.Assume.assumeTrue("capture".equals(InstrumentationRegistry.getArguments().getString("upgradePhase")));
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject captured = new JSONObject();
        for (String name : new String[]{"saved_targets", "ScannerActivity", "scan_history"}) {
            JSONObject values = new JSONObject();
            for (var entry : target.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet())
                values.put(entry.getKey(), digest(canonical(entry.getValue())));
            captured.put(name, values);
        }
        captured.put("historyRows", historyRows(target));
        assertTrue(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("upgrade_probe", Context.MODE_PRIVATE).edit()
                .putString("baseline", captured.toString()).commit());
        android.os.Bundle result = new android.os.Bundle();
        result.putString("upgrade_baseline", captured.getJSONObject("saved_targets").length() + " registration preference keys, "
                + captured.getJSONObject("historyRows").length() + " completed history rows");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
    }

    @Test public void verifyAfterUpgrade() throws Exception {
        org.junit.Assume.assumeTrue("verify".equals(InstrumentationRegistry.getArguments().getString("upgradePhase")));
        String baseline = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("upgrade_probe", Context.MODE_PRIVATE).getString("baseline", null);
        assertNotNull("Capture baseline against the old app before installing the signed upgrade", baseline);
        JSONObject captured = new JSONObject(baseline);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[]{"saved_targets", "ScannerActivity", "scan_history"}) {
            JSONObject values = captured.getJSONObject(name);
            Map<String, ?> current = target.getSharedPreferences(name, Context.MODE_PRIVATE).getAll();
            var keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                assertTrue("Preserved preference " + name + "/" + key, current.containsKey(key));
                assertEquals("Unchanged preference " + name + "/" + key, values.getString(key), digest(canonical(current.get(key))));
            }
        }
        JSONObject oldRows = captured.getJSONObject("historyRows");
        JSONObject currentRows = historyRows(target);
        var ids = oldRows.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            assertEquals("Preserved completed history row", oldRows.getString(id), currentRows.optString(id));
        }
        android.os.Bundle result = new android.os.Bundle();
        result.putString("upgrade_preserved", "saved preference keys and " + oldRows.length() + " completed history rows");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
        target.deleteSharedPreferences("upgrade_probe");
    }

    private static JSONObject historyRows(Context context) throws Exception {
        JSONObject result = new JSONObject();
        java.io.File file = context.getDatabasePath("scan_history.db");
        if (!file.exists()) return result;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor rows = db.rawQuery("SELECT id,title,legacy_body FROM sessions WHERE state<>'running' ORDER BY id", null)) {
            while (rows.moveToNext()) {
                String id = rows.getString(0);
                StringBuilder body = new StringBuilder().append(rows.getString(1)).append('\n').append(rows.getString(2));
                try (Cursor observations = db.rawQuery("SELECT observation_key,name,metadata,reason FROM observations "
                        + "WHERE session_id=? ORDER BY position", new String[]{id})) {
                    while (observations.moveToNext()) for (int column = 0; column < 4; column++)
                        body.append('\n').append(observations.getString(column));
                }
                result.put(id, digest(body.toString()));
            }
        }
        return result;
    }

    private static String canonical(Object value) {
        if (value instanceof Set<?> values) {
            ArrayList<String> sorted = new ArrayList<>();
            for (Object entry : values) sorted.add(String.valueOf(entry));
            java.util.Collections.sort(sorted);
            return sorted.toString();
        }
        return String.valueOf(value);
    }

    private static String digest(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        for (byte item : hash) output.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return output.toString();
    }
}
