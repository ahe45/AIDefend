package com.glassesscanner;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ScanHistory extends SQLiteOpenHelper {
    static final int MAX_ROWS = 100;
    private static final String RUNNING = "running";
    private static ScanHistory instance;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences legacyHistory;
    private final ScanPreferences preferences;

    record Entry(String id, String title, String summary, String legacyBody) {}
    record Detail(Entry entry, String observations) {}
    interface Callback<T> { void accept(T result); }
    interface Query<T> { T run(SQLiteDatabase db); }

    static synchronized ScanHistory get(Context context) {
        if (instance == null) instance = new ScanHistory(context.getApplicationContext(), "scan_history");
        return instance;
    }

    ScanHistory(Context context, String storageName) {
        super(context, storageName + ".db", null, 1);
        legacyHistory = context.getSharedPreferences(storageName, Context.MODE_PRIVATE);
        preferences = new ScanPreferences(context);
        enqueue(db -> {
            recover(db);
            return true;
        }, null);
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, started INTEGER NOT NULL, ended INTEGER, "
                + "mode TEXT NOT NULL, state TEXT NOT NULL, reason TEXT NOT NULL, total INTEGER NOT NULL DEFAULT 0, "
                + "candidates INTEGER NOT NULL DEFAULT 0, omitted INTEGER NOT NULL DEFAULT 0, title TEXT, legacy_body TEXT)");
        db.execSQL("CREATE TABLE observations (session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, "
                + "observation_key TEXT NOT NULL, position INTEGER NOT NULL, name TEXT NOT NULL, metadata TEXT NOT NULL, "
                + "reason TEXT NOT NULL, PRIMARY KEY(session_id, observation_key))");
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int previous, int next) {
        throw new IllegalStateException("Missing history migration " + previous + " -> " + next);
    }

    @Override public synchronized void close() {
        io.shutdown();
        super.close();
    }

    private void recover(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            ContentValues interrupted = new ContentValues();
            interrupted.put("state", "interrupted");
            interrupted.put("reason", "스캔 중단 · 마지막으로 저장한 결과");
            interrupted.put("ended", System.currentTimeMillis());
            db.update("sessions", interrupted, "state=?", new String[]{RUNNING});
            try (Cursor marker = db.rawQuery("SELECT key FROM metadata WHERE key='legacy_imported'", null)) {
                if (!marker.moveToFirst()) importLegacy(db);
            }
            trim(db, preferences.retention());
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void importLegacy(SQLiteDatabase db) {
        String raw = legacyHistory.getString("sessions", "[]");
        try {
            JSONArray rows = new JSONArray(raw);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                ContentValues values = session("legacy-" + i, -i, "", "legacy", "이전 버전에서 가져온 기록");
                values.put("title", row.optString("title"));
                values.put("legacy_body", row.optString("body"));
                db.insertWithOnConflict("sessions", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.execSQL("INSERT INTO metadata(key) VALUES ('legacy_imported')");
        } catch (JSONException invalid) {
            Log.w("GlassesScanner", "Legacy history could not be imported");
        }
    }

    void begin(String id, long started, String mode) {
        enqueue(db -> {
            db.insertOrThrow("sessions", null, session(id, started, mode, RUNNING, "스캔 중"));
            return true;
        }, null);
    }

    private ContentValues session(String id, long started, String mode, String state, String reason) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("started", started);
        values.put("mode", mode);
        values.put("state", state);
        values.put("reason", reason);
        return values;
    }

    void save(String id, long ended, String state, String reason, int total, int candidates, int omitted,
              List<ScanObservation> rows, Callback<Boolean> callback) {
        enqueue(db -> {
            db.beginTransaction();
            try {
                ContentValues values = new ContentValues();
                if (ended > 0) {
                    values.put("ended", ended);
                    values.put("state", state);
                    values.put("reason", reason);
                }
                values.put("total", total);
                values.put("candidates", candidates);
                values.put("omitted", omitted);
                int updated = db.update("sessions", values, "id=? AND state=?", new String[]{id, RUNNING});
                if (updated != 1) return false;
                db.delete("observations", "session_id=?", new String[]{id});
                for (int i = 0; i < rows.size(); i++) {
                    ScanObservation row = rows.get(i);
                    ContentValues fields = new ContentValues();
                    fields.put("session_id", id);
                    fields.put("observation_key", row.key());
                    fields.put("position", i);
                    fields.put("name", row.name());
                    fields.put("metadata", row.metadata());
                    StringBuilder evidence = new StringBuilder();
                    for (GlassesMatcher.Match match : row.matches()) {
                        if (evidence.length() > 0) evidence.append('\n');
                        evidence.append(match.label()).append(" · ").append(match.reason());
                    }
                    if (evidence.length() > 0) evidence.append('\n');
                    evidence.append(row.radioEvidence());
                    fields.put("reason", evidence.toString());
                    db.insertOrThrow("observations", null, fields);
                }
                if (ended > 0) trim(db, preferences.retention());
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }, callback);
    }

    void list(Callback<List<Entry>> callback) {
        enqueue(db -> {
            List<Entry> entries = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("SELECT * FROM sessions WHERE state<>? ORDER BY started DESC", new String[]{RUNNING})) {
                while (cursor.moveToNext()) entries.add(entry(cursor));
            }
            return entries;
        }, callback);
    }

    void detail(String id, Callback<Detail> callback) {
        enqueue(db -> {
            Entry entry;
            try (Cursor cursor = db.rawQuery("SELECT * FROM sessions WHERE id=? AND state<>?", new String[]{id, RUNNING})) {
                if (!cursor.moveToFirst()) return null;
                entry = entry(cursor);
            }
            if (entry.legacyBody() != null) return new Detail(entry, entry.legacyBody());
            StringBuilder observations = new StringBuilder();
            try (Cursor cursor = db.rawQuery("SELECT name,metadata,reason FROM observations WHERE session_id=? ORDER BY position",
                    new String[]{id})) {
                while (cursor.moveToNext()) {
                    if (observations.length() > 0) observations.append("\n\n");
                    observations.append(cursor.getString(0)).append("\n").append(cursor.getString(1));
                    String reason = cursor.getString(2);
                    if (!reason.isEmpty()) observations.append("\n").append(reason);
                }
            }
            return new Detail(entry, observations.toString());
        }, callback);
    }

    void delete(String id, Callback<Boolean> callback) {
        enqueue(db -> {
            db.delete("sessions", id == null ? "state<>?" : "state<>? AND id=?",
                    id == null ? new String[]{RUNNING} : new String[]{RUNNING, id});
            return true;
        }, callback);
    }

    void trim(int limit, Callback<Boolean> callback) {
        enqueue(db -> {
            trim(db, limit);
            return true;
        }, callback);
    }

    private static void trim(SQLiteDatabase db, int limit) {
        db.execSQL("DELETE FROM sessions WHERE id IN (SELECT id FROM sessions WHERE state<>? "
                + "ORDER BY started DESC LIMIT -1 OFFSET ?)", new Object[]{RUNNING, limit});
    }

    private Entry entry(Cursor cursor) {
        String id = text(cursor, "id");
        String title = text(cursor, "title");
        if (title == null) {
            title = date(number(cursor, "started")) + " · " + text(cursor, "mode");
        }
        String summary = text(cursor, "reason");
        if (text(cursor, "legacy_body") == null) {
            summary += "\n관측 " + number(cursor, "total") + "건 · 후보 " + number(cursor, "candidates") + "건";
            long ended = number(cursor, "ended");
            if (ended > 0) summary += "\n종료 " + date(ended);
            long omitted = number(cursor, "omitted");
            if (omitted > 0) summary += " · 상세 생략 " + omitted + "건";
        }
        return new Entry(id, title, summary, text(cursor, "legacy_body"));
    }

    private String date(long epoch) { return DateFormat.getDateTimeInstance().format(new Date(epoch)); }
    private String text(Cursor cursor, String key) { return cursor.getString(cursor.getColumnIndexOrThrow(key)); }
    private long number(Cursor cursor, String key) { return cursor.getLong(cursor.getColumnIndexOrThrow(key)); }

    private <T> void enqueue(Query<T> query, Callback<T> callback) {
        io.execute(() -> {
            T result = null;
            try {
                result = query.run(getWritableDatabase());
            } catch (RuntimeException failed) {
                Log.w("GlassesScanner", "Unable to access scan history", failed);
            }
            T output = result;
            if (callback != null) main.post(() -> callback.accept(output));
        });
    }
}
