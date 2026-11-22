package com.uplusys.deviceguard;

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
import java.util.concurrent.TimeUnit;

final class ScanHistory extends SQLiteOpenHelper {
    private static final String RUNNING = "running";
    private static ScanHistory instance;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences legacyHistory;
    private final ScanPreferences preferences;

    record Entry(String id, String title, String summary, String legacyBody, long started, long ended,
                 int total, int candidates, boolean fullResults, boolean olderCandidateOnly) {}
    record Result(int position, String category, boolean candidate, boolean classificationKnown, String transport,
                  Integer rssi, String seen, String evidence, String legacyMetadata) {}
    record Detail(Entry entry, String observations, List<Result> results) {}
    interface Callback<T> { void accept(T result); }
    interface Query<T> { T run(SQLiteDatabase db); }

    static synchronized ScanHistory get(Context context) {
        if (instance == null) instance = new ScanHistory(context.getApplicationContext(), "scan_history");
        return instance;
    }

    ScanHistory(Context context, String storageName) {
        super(context, storageName + ".db", null, 3);
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
                + "candidates INTEGER NOT NULL DEFAULT 0, omitted INTEGER NOT NULL DEFAULT 0, title TEXT, legacy_body TEXT, "
                + "selection TEXT, paths TEXT, completed_paths TEXT, rule_version TEXT, full_results INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE observations (session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, "
                + "observation_key TEXT NOT NULL, position INTEGER NOT NULL, name TEXT NOT NULL, metadata TEXT NOT NULL, "
                + "reason TEXT NOT NULL, candidate INTEGER, transport TEXT, rssi INTEGER, seen TEXT, "
                + "PRIMARY KEY(session_id, observation_key))");
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int previous, int next) {
        if (previous < 2) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN selection TEXT");
            db.execSQL("ALTER TABLE sessions ADD COLUMN paths TEXT");
            db.execSQL("ALTER TABLE sessions ADD COLUMN completed_paths TEXT");
            db.execSQL("ALTER TABLE sessions ADD COLUMN rule_version TEXT");
        }
        if (previous < 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN full_results INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE observations ADD COLUMN candidate INTEGER");
            db.execSQL("ALTER TABLE observations ADD COLUMN transport TEXT");
            db.execSQL("ALTER TABLE observations ADD COLUMN rssi INTEGER");
            db.execSQL("ALTER TABLE observations ADD COLUMN seen TEXT");
        }
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
            trimExpired(db, preferences.retentionDays());
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
            Log.w("DeviceGuard", "Legacy history could not be imported");
        }
    }

    void begin(String id, long started, String mode) {
        begin(id, started, mode, null);
    }

    void begin(String id, long started, String mode, SessionConfig config) {
        enqueue(db -> {
            ContentValues values = session(id, started, mode, RUNNING, "스캔 중");
            if (config != null) {
                values.put("selection", config.categorySummary());
                values.put("rule_version", config.ruleVersion());
            }
            db.insertOrThrow("sessions", null, values);
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
        save(id, ended, state, reason, total, candidates, omitted, rows, null, java.util.Map.of(), java.util.Set.of(), callback);
    }

    void save(String id, long ended, String state, String reason, int total, int candidates, int omitted,
              List<ScanObservation> rows, SessionConfig config,
              java.util.Map<RadioScanner.Mode, RadioScanner.PathStatus> paths,
              java.util.Set<RadioScanner.Mode> completed, Callback<Boolean> callback) {
        List<ScanObservation> retained = List.copyOf(rows);
        String pathSummary = paths.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().label + " · " + pathLabel(entry.getValue().state().name()))
                .collect(java.util.stream.Collectors.joining("\n"));
        String completedSummary = completed.stream().sorted().map(path -> path.label)
                .collect(java.util.stream.Collectors.joining(" + "));
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
                values.put("full_results", 1);
                values.put("paths", pathSummary);
                values.put("completed_paths", completedSummary);
                if (config != null) {
                    values.put("selection", config.categorySummary());
                    values.put("rule_version", config.ruleVersion());
                }
                int updated = db.update("sessions", values, "id=? AND state=?", new String[]{id, RUNNING});
                if (updated != 1) return false;
                db.delete("observations", "session_id=?", new String[]{id});
                long wallOffset = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
                DateFormat seenFormat = DateFormat.getDateTimeInstance();
                for (int i = 0; i < retained.size(); i++) {
                    ScanObservation row = retained.get(i);
                    ContentValues fields = new ContentValues();
                    fields.put("session_id", id);
                    fields.put("observation_key", "observation-" + i);
                    fields.put("position", i);
                    String categories = row.matches().stream().filter(DeviceMatcher.Match::classified)
                            .map(match -> match.category().label).distinct().collect(java.util.stream.Collectors.joining(" · "));
                    fields.put("name", categories.isEmpty() ? "미분류 신호" : categories);
                    String seen = row.signal().observedAtElapsed() > 0
                            ? seenFormat.format(new Date(wallOffset + row.signal().observedAtElapsed())) : "시각 정보 없음";
                    if (row.signal().previousResult()) seen = "이전 결과 · " + seen;
                    fields.put("metadata", row.metadata() + "\n마지막 스캔  " + seen);
                    fields.put("candidate", row.candidate() ? 1 : 0);
                    fields.put("transport", row.signal().source().label);
                    fields.put("rssi", row.signal().rssi());
                    fields.put("seen", seen);
                    StringBuilder evidence = new StringBuilder("스캔 근거\n");
                    evidence.append(row.matches().stream().flatMap(match -> match.evidence().stream())
                            .map(DeviceMatcher.Evidence::type).distinct().collect(java.util.stream.Collectors.joining(" · ")));
                    evidence.append("\n근거 상태  ").append(row.matches().stream().map(match -> match.evidenceLevel().label)
                            .distinct().collect(java.util.stream.Collectors.joining(" · ")));
                    if (DeviceMatcher.conflict(row.matches())) evidence.append("\n유형 충돌 · 경보 보류");
                    fields.put("reason", row.matches().isEmpty() ? "" : evidence.toString());
                    db.insertOrThrow("observations", null, fields);
                }
                if (ended > 0) trimExpired(db, preferences.retentionDays());
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }, callback);
    }

    private static String pathLabel(String state) {
        return switch (state) {
            case "NOT_RUN" -> "검색 미실행";
            case "RUNNING" -> "검색 중";
            case "COMPLETE" -> "완료";
            case "PERMISSION_REQUIRED" -> "권한 필요";
            case "THROTTLED" -> "검색 요청 제한";
            case "FAILED" -> "검색 실패";
            case "STALE" -> "이전 결과";
            case "STOPPED" -> "중지";
            default -> state;
        };
    }

    void list(Callback<List<Entry>> callback) {
        enqueue(db -> {
            trimExpired(db, preferences.retentionDays());
            List<Entry> entries = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("SELECT * FROM sessions WHERE state<>? ORDER BY started DESC", new String[]{RUNNING})) {
                while (cursor.moveToNext()) entries.add(entry(cursor));
            }
            return entries;
        }, callback);
    }

    void detail(String id, Callback<Detail> callback) {
        enqueue(db -> {
            trimExpired(db, preferences.retentionDays());
            Entry entry;
            try (Cursor cursor = db.rawQuery("SELECT * FROM sessions WHERE id=? AND state<>?", new String[]{id, RUNNING})) {
                if (!cursor.moveToFirst()) return null;
                entry = entry(cursor);
            }
            if (entry.legacyBody() != null) return new Detail(entry, historyText(entry.legacyBody()), List.of());
            StringBuilder observations = new StringBuilder();
            List<Result> results = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("SELECT * FROM observations WHERE session_id=? ORDER BY position",
                    new String[]{id})) {
                while (cursor.moveToNext()) {
                    if (observations.length() > 0) observations.append("\n\n");
                    String name = text(cursor, "name");
                    String category = "미분류 관측".equals(name) ? "미분류 신호" : name;
                    String metadata = historyText(text(cursor, "metadata"));
                    String reason = historyText(text(cursor, "reason"));
                    observations.append("기기/유형  ").append(category).append("\n스캔 정보  ").append(metadata);
                    if (!reason.isEmpty()) observations.append("\n").append(reason);
                    boolean structured = !cursor.isNull(cursor.getColumnIndexOrThrow("candidate"));
                    int rssiColumn = cursor.getColumnIndexOrThrow("rssi");
                    results.add(new Result((int) number(cursor, "position"), category,
                            structured ? number(cursor, "candidate") != 0 : entry.olderCandidateOnly(),
                            structured || entry.olderCandidateOnly(), text(cursor, "transport"),
                            cursor.isNull(rssiColumn) ? null : cursor.getInt(rssiColumn), text(cursor, "seen"),
                            reason, structured ? null : metadata));
                }
            }
            return new Detail(entry, observations.toString(), List.copyOf(results));
        }, callback);
    }

    void delete(String id, Callback<Boolean> callback) {
        enqueue(db -> {
            db.delete("sessions", id == null ? "state<>?" : "state<>? AND id=?",
                    id == null ? new String[]{RUNNING} : new String[]{RUNNING, id});
            return true;
        }, callback);
    }

    void changeRetentionDays(int days, Callback<Boolean> callback) {
        enqueue(db -> {
            trimExpired(db, days);
            preferences.retentionDays(days);
            return true;
        }, callback);
    }

    private static void trimExpired(SQLiteDatabase db, int days) {
        if (days == ScanPreferences.UNLIMITED_RETENTION) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        // Imported text-only history has no reliable timestamp.
        db.execSQL("DELETE FROM sessions WHERE state<>? AND started>0 AND COALESCE(NULLIF(ended, 0), started) < ?",
                new Object[]{RUNNING, cutoff});
    }

    private Entry entry(Cursor cursor) {
        String id = text(cursor, "id");
        String title = text(cursor, "title");
        if (title == null) {
            title = date(number(cursor, "started")) + " · " + text(cursor, "mode");
        }
        String summary = text(cursor, "reason");
        String selected = text(cursor, "selection");
        if (selected != null) summary += "\n선택 유형  " + selected;
        String routes = text(cursor, "paths");
        if (routes != null && !routes.isBlank()) summary += "\n경로별 상태\n" + routes;
        String completed = text(cursor, "completed_paths");
        if (completed != null && !completed.isBlank()) summary += "\n완료한 경로  " + completed;
        String version = text(cursor, "rule_version");
        if (version != null) summary += "\n규칙 버전  " + version;
        if (text(cursor, "legacy_body") == null) {
            summary += "\n스캔 " + number(cursor, "total") + "건 · 의심 기기 " + number(cursor, "candidates") + "건";
            long ended = number(cursor, "ended");
            if (ended > 0) summary += "\n종료 " + date(ended);
            long omitted = number(cursor, "omitted");
            if (omitted > 0) summary += " · 상세 생략 " + omitted + "건";
        }
        return new Entry(id, historyText(title), historyText(summary), text(cursor, "legacy_body"),
                number(cursor, "started"), number(cursor, "ended"), (int) number(cursor, "total"),
                (int) number(cursor, "candidates"), number(cursor, "full_results") != 0, version != null);
    }

    private static String historyText(String value) {
        // Older sessions store display labels as text. Convert them when displaying the history.
        return DetectionText.display(value)
                .replace("탐지 근거", "스캔 근거")
                .replace("내 탐지 대상", "종류 미지정")
                .replace("내 스캔 대상", "종류 미지정")
                .replace("통합 관측", "통합 스캔")
                .replace("마지막 관측", "마지막 스캔")
                .replace("관측 방식", "스캔 방식")
                .replace("관측 정보", "스캔 정보")
                .replace("관측 이력", "스캔 이력")
                .replace("관측 완료", "스캔 완료");
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
                Log.w("DeviceGuard", "Unable to access scan history", failed);
            }
            T output = result;
            if (callback != null) main.post(() -> callback.accept(output));
        });
    }
}
