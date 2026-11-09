package cn.openpocket.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Durable snapshots in private app storage, isolated by server and account. */
final class HistoryStore extends SQLiteOpenHelper {
    HistoryStore(Context context, String server, String email) {
        super(context.getApplicationContext(), databaseName(server, email), null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    private static String databaseName(String server, String email) {
        try {
            URI uri = URI.create(server);
            String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (port != -1 && !(port == 443 && "https".equalsIgnoreCase(uri.getScheme())) &&
                !(port == 80 && "http".equalsIgnoreCase(uri.getScheme()))) origin += ":" + port;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (origin + "\n" + email.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder("history-");
            for (byte b : digest) name.append(String.format(Locale.ROOT, "%02x", b & 255));
            return name + ".db";
        } catch (Exception e) { throw new IllegalArgumentException("Invalid history account", e); }
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE state (name TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE chats (id TEXT PRIMARY KEY, revision TEXT NOT NULL, saved_at INTEGER NOT NULL, running INTEGER NOT NULL)");
        // Small rows also support conversations larger than Android's CursorWindow.
        db.execSQL("CREATE TABLE chat_parts (id TEXT NOT NULL, part INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY (id, part))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    void putState(String name, String json) {
        ContentValues values = new ContentValues(); values.put("name", name); values.put("payload", json);
        if(getWritableDatabase().insertWithOnConflict("state", null, values, SQLiteDatabase.CONFLICT_REPLACE)<0)throw new android.database.sqlite.SQLiteException("无法保存本地记录，请检查手机存储空间。");
    }
    String state(String name) {
        try (Cursor cursor = getReadableDatabase().query("state", new String[]{"payload"}, "name=?", new String[]{name}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }
    JSONArray list() {
        try { return new JSONArray(state("list")); } catch (Exception ignored) { return new JSONArray(); }
    }
    JSONArray models() {
        try { return new JSONArray(state("models")); } catch (Exception ignored) { return new JSONArray(); }
    }
    HistoryRepository.Snapshot chat(String id) {
        HistoryRepository.Stamp stamp=stamp(id);if(stamp==null)return null;
        StringBuilder json=new StringBuilder();
        try (Cursor cursor = getReadableDatabase().query("chat_parts", new String[]{"payload"}, "id=?", new String[]{id}, null, null, "part ASC")) {
            while(cursor.moveToNext())json.append(cursor.getString(0));
        }
        try{return new HistoryRepository.Snapshot(new JSONObject(json.toString()),stamp.revision,stamp.savedAt);}
        catch(Exception ignored){return null;}
    }
    HistoryRepository.Stamp stamp(String id) {
        try (Cursor cursor = getReadableDatabase().query("chats", new String[]{"revision", "saved_at", "running"}, "id=?", new String[]{id}, null, null, null)) {
            return cursor.moveToFirst() ? new HistoryRepository.Stamp(cursor.getString(0), cursor.getLong(1), cursor.getInt(2) != 0) : null;
        }
    }
    void putChat(String id, JSONObject response, String revision, long savedAt) {
        ContentValues values = new ContentValues(); values.put("id", id);
        values.put("revision", revision); values.put("saved_at", savedAt); values.put("running", ChatData.running(response) ? 1 : 0);
        SQLiteDatabase db=getWritableDatabase();String json=response.toString();db.beginTransaction();
        try {
            if(db.insertWithOnConflict("chats", null, values, SQLiteDatabase.CONFLICT_REPLACE)<0)throw new android.database.sqlite.SQLiteException("无法保存对话，请检查手机存储空间。");
            db.delete("chat_parts","id=?",new String[]{id});
            int part=0;
            for(int start=0;start<json.length();) {
                int end=Math.min(json.length(),start+65536);
                if(end<json.length()&&Character.isHighSurrogate(json.charAt(end-1)))end--;
                ContentValues chunk=new ContentValues();chunk.put("id",id);chunk.put("part",part++);chunk.put("payload",json.substring(start,end));
                db.insertOrThrow("chat_parts",null,chunk);start=end;
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    Set<String> cachedIds() {
        Set<String> result = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().query("chats", new String[]{"id"}, null, null, null, null, null)) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return result;
    }
    void removeChat(String id) { SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{db.delete("chats", "id=?", new String[]{id});db.delete("chat_parts","id=?",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();} }
    void clear() {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { db.delete("chat_parts",null,null);db.delete("chats", null, null); db.delete("state", null, null); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
}
