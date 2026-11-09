package cn.openpocket.app;

import android.content.Context;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Check small list revisions before downloading a conversation body again. */
final class HistoryRepository {
    interface Source { JSONObject fetch(String id) throws Exception; }
    static class Stamp {
        final String revision;
        final long savedAt;
        final boolean running;
        Stamp(String revision, long savedAt, boolean running) {
            this.revision = revision; this.savedAt = savedAt; this.running = running;
        }
    }
    static final class Snapshot extends Stamp {
        final JSONObject response;
        final int bytes;
        Snapshot(JSONObject response, String revision, long savedAt) {
            super(revision, savedAt, ChatData.running(response));this.response = response;bytes = Math.max(1, response.toString().length() * 2);
        }
    }
    private final HistoryStore store;
    private final Source source;
    private final LruCache<String, Snapshot> recent = new LruCache<String, Snapshot>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String id, Snapshot snapshot) { return snapshot.bytes; }
    };
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private volatile boolean closed;
    HistoryRepository(Context context, String server, String email, Source source) {
        store = new HistoryStore(context, server, email); this.source = source;
    }
    boolean closed() { return closed; }
    private void checkOpen() throws InterruptedException { if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    synchronized JSONArray list() throws Exception { checkOpen(); return store.list(); }
    synchronized JSONArray models() throws Exception { checkOpen(); return store.models(); }
    synchronized Set<String> cachedIds() throws Exception { checkOpen(); return store.cachedIds(); }
    synchronized Snapshot cached(String id) throws Exception {
        checkOpen();Snapshot snapshot = recent.get(id);
        if (snapshot == null) { snapshot = store.chat(id);if (snapshot != null) recent.put(id, snapshot); }
        return snapshot;
    }
    synchronized void saveList(JSONArray list) throws Exception { checkOpen(); store.putState("list", list.toString()); }
    synchronized void saveModels(JSONArray models) throws Exception { checkOpen(); store.putState("models", models.toString()); }
    synchronized JSONObject preferences() throws Exception { checkOpen();try{return new JSONObject(store.state("preferences"));}catch(Exception ignored){return new JSONObject();} }
    synchronized void preferences(JSONObject settings) throws Exception { checkOpen();store.putState("preferences",settings.toString()); }
    synchronized JSONObject usage()throws Exception{checkOpen();try{return new JSONObject(store.state("usage"));}catch(Exception ignored){return new JSONObject();}}
    synchronized void usage(JSONObject value)throws Exception{checkOpen();store.putState("usage",value.toString());}
    synchronized void removeChat(String id) throws Exception { checkOpen(); store.removeChat(id);recent.remove(id); }
    void renameTitle(String id,String title) throws Exception {
        synchronized(locks.computeIfAbsent(id,ignored->new Object())){
            synchronized(this){
                checkOpen();JSONArray list=store.list();JSONObject item=summary(list,id);if(item!=null){item.put("title",title);store.putState("list",list.toString());}
                updateCachedTitle(id,title);
            }
        }
    }
    void selectModel(String id,String model) throws Exception {
        synchronized(locks.computeIfAbsent(id,ignored->new Object())){
            synchronized(this){
                checkOpen();Snapshot previous=cached(id);if(previous==null)return;
                JSONObject response=new JSONObject(previous.response.toString());
                response.getJSONObject("chat").put("models",new JSONArray().put(model));
                Snapshot updated=new Snapshot(response,previous.revision,System.currentTimeMillis());
                store.putChat(id,response,updated.revision,updated.savedAt);recent.put(id,updated);
            }
        }
    }
    private Snapshot updateCachedTitle(String id,String title) throws Exception {
        Snapshot previous=cached(id);if(previous==null)return null;
        JSONObject chat=previous.response.optJSONObject("chat");if(chat==null||title.equals(chat.optString("title")))return previous;
        JSONObject response=new JSONObject(previous.response.toString());response.put("title",title);response.getJSONObject("chat").put("title",title);
        Snapshot updated=new Snapshot(response,previous.revision,System.currentTimeMillis());store.putChat(id,response,updated.revision,updated.savedAt);recent.put(id,updated);return updated;
    }
    synchronized boolean needsRefresh(String id, JSONObject summary) throws Exception { checkOpen();return needsRefresh(store.stamp(id), summary); }

    static JSONObject summary(JSONArray list, String id) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item;
        }
        return null;
    }
    private static String revision(JSONObject summary) {
        if (summary == null || summary.isNull("updated_at")) return "";
        String value = summary.optString("updated_at", "");
        return value.equals("0") || value.equals("null") ? "" : value;
    }
    static boolean needsRefresh(Stamp snapshot, JSONObject summary) {
        if (snapshot == null) return true;
        if ((summary != null && summary.optBoolean("active")) || snapshot.running) return true;
        String revision = revision(summary);
        // Older servers may not expose a revision. Recheck those at most once a minute.
        if (revision.isEmpty()) return System.currentTimeMillis() - snapshot.savedAt >= 60000;
        try { return new BigDecimal(revision).compareTo(new BigDecimal(snapshot.revision)) > 0; }
        catch (NumberFormatException ignored) { return !revision.equals(snapshot.revision); }
    }
    Snapshot refresh(String id, JSONObject summary, boolean force) throws Exception {
        synchronized (locks.computeIfAbsent(id, ignored -> new Object())) {
            Snapshot previous = cached(id);
            if (!force && !needsRefresh(previous, summary)) {
                // Renaming does not advance message revisions on this server.
                if(summary!=null&&summary.has("title"))synchronized(this){checkOpen();return updateCachedTitle(id,summary.optString("title"));}
                return previous;
            }
            JSONObject response = source.fetch(id);
            if (!id.equals(response.optString("id")) || response.optJSONObject("chat") == null)
                throw new Exception("服务器返回的对话不完整，保留手机上的历史记录。");
            synchronized (this) {
                checkOpen();
                String actualRevision = revision(response);
                Snapshot snapshot = new Snapshot(response, actualRevision.isEmpty() ? revision(summary) : actualRevision, System.currentTimeMillis());
                store.putChat(id, response, snapshot.revision, snapshot.savedAt);
                recent.put(id, snapshot);
                return snapshot;
            }
        }
    }
    // In-flight HTTP responses cannot write snapshots back after logout.
    synchronized void close(boolean clear) {
        closed = true;
        try { if (clear) store.clear(); } finally { recent.evictAll();store.close(); }
    }
}
