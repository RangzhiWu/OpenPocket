package cn.openpocket.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class HistoryCacheChecks {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static JSONObject detail(String id, String text, boolean done) throws Exception {
        JSONObject user = new JSONObject().put("id", "u").put("role", "user").put("content", "离线历史测试").put("parentId", JSONObject.NULL);
        JSONObject answer = new JSONObject().put("id", "a").put("role", "assistant").put("parentId", "u").put("content", text).put("done", done)
            .put("output", new JSONArray().put(new JSONObject().put("type", "open_webui:code_interpreter").put("id", "tool")
                .put("code", "printf history").put("output", "本地工具输出").put("status", "completed")));
        return new JSONObject().put("id", id).put("chat", new JSONObject().put("title", "本地历史 " + id)
            .put("models", new JSONArray().put("test-model"))
            .put("history", new JSONObject().put("currentId", "a").put("messages", new JSONObject().put("u", user).put("a", answer))));
    }
    static void run(Context context) throws Exception {
        String server = "https://cache.example.invalid", email = "history-check@example.invalid", id = "persisted";
        AtomicInteger requests = new AtomicInteger();AtomicBoolean offline = new AtomicBoolean();
        AtomicReference<JSONObject> remote = new AtomicReference<>(detail(id, "第一条已保存的消息", true));
        HistoryRepository.Source source = chat -> { requests.incrementAndGet();if (offline.get()) throw new IOException("offline");return remote.get(); };
        HistoryRepository repository = new HistoryRepository(context, server, email, source);
        repository.close(true);repository = new HistoryRepository(context, server, email, source);
        JSONObject summary = new JSONObject().put("id", id).put("updated_at", 100);
        JSONArray list = new JSONArray().put(summary);
        repository.saveList(list);repository.saveModels(new JSONArray().put(new JSONObject().put("id", "test-model")));
        repository.refresh(id, summary, false);
        check(requests.get() == 1, "first synchronization downloads body once");
        repository.refresh(id, summary, false);repository.refresh(id, summary, false);
        check(requests.get() == 1, "unchanged polling does not download history");
        repository.close(false);
        // New repository and SQLite connection simulate losing all in-memory state.
        repository = new HistoryRepository(context, "https://CACHE.example.invalid:443/", " HISTORY-CHECK@example.invalid ", source);
        offline.set(true);
        check(repository.list().getJSONObject(0).getString("id").equals(id), "list survives reopening storage");
        check(repository.models().length() == 1, "models survive reopening storage");
        JSONObject saved = repository.refresh(id, summary, false).response;
        check(requests.get() == 1, "reopening unchanged history needs no network");
        check(ChatData.messages(saved).get(1).getString("content").equals("第一条已保存的消息"), "offline message content persists");
        check(ChatData.messages(saved).get(1).getJSONArray("output").getJSONObject(0).getString("output").equals("本地工具输出"), "full tool output persists");
        String messagesBeforeRename=saved.getJSONObject("chat").getJSONObject("history").toString();
        repository.renameTitle(id,"手机修改的名称 😀");
        check(repository.list().getJSONObject(0).getString("title").equals("手机修改的名称 😀"),"renaming updates saved list");
        check(repository.cached(id).response.getJSONObject("chat").getJSONObject("history").toString().equals(messagesBeforeRename),"renaming preserves messages and tool output");
        repository.close(false);repository=new HistoryRepository(context,server,email,source);
        check(repository.cached(id).response.getJSONObject("chat").getString("title").equals("手机修改的名称 😀"),"renamed detail survives reopening");
        check(repository.list().getJSONObject(0).getString("title").equals("手机修改的名称 😀"),"renamed list survives reopening");
        summary.put("title","电脑修改的名称");saved=repository.refresh(id,summary,false).response;
        check(requests.get()==1&&saved.getJSONObject("chat").getString("title").equals("电脑修改的名称"),"title syncs without revision change or downloading messages");
        check(saved.getJSONObject("chat").getJSONObject("history").toString().equals(messagesBeforeRename),"title sync preserves entire history");
        String untouched=saved.toString();
        HistoryRepository modelFixture=new HistoryRepository(context,server,email,chat->detail(chat,"other conversation",true));
        modelFixture.refresh("model-other",null,true);modelFixture.close(false);
        repository.selectModel("model-other","server-task-assistant");
        check(repository.cached(id).response.toString().equals(untouched),"another conversation model change preserves this snapshot");
        repository.selectModel(id,"server-codex-agent");
        saved=repository.cached(id).response;
        check(saved.getJSONObject("chat").getJSONArray("models").getString(0).equals("server-codex-agent"),"model selection saved in this snapshot");
        check(saved.getJSONObject("chat").getJSONObject("history").toString().equals(messagesBeforeRename),"model selection preserves messages and tool output");
        repository.close(false);repository=new HistoryRepository(context,server,email,source);
        check(repository.cached(id).response.getJSONObject("chat").getJSONArray("models").getString(0).equals("server-codex-agent"),"per-chat model survives database reopening");
        check(repository.cached("model-other").response.getJSONObject("chat").getJSONArray("models").getString(0).equals("server-task-assistant"),"other model remains independent after reopening");
        summary.remove("title");
        summary.put("updated_at", 101);
        boolean failed = false;try { repository.refresh(id, summary, false); } catch (IOException expected) { failed = true; }
        check(failed && repository.cached(id).response.toString().equals(saved.toString()), "network failure preserves previous snapshot");
        offline.set(false);remote.set(detail(id, "电脑端的新消息", true));repository.refresh(id, summary, false);
        check(ChatData.messages(repository.cached(id).response).get(1).getString("content").equals("电脑端的新消息"), "changed revision updates local messages");
        int before = requests.get();repository.refresh(id, summary, false);check(requests.get() == before, "new revision is remembered");
        repository.refresh(id, new JSONObject().put("id", id).put("updated_at", 100), false);check(requests.get() == before, "stale background metadata cannot force a reload of a newer snapshot");
        repository.refresh(id, summary, true);check(requests.get() == before + 1, "manual refresh revalidates even same revision");
        summary.put("active", true);remote.set(detail(id, "生成中的消息", false));repository.refresh(id, summary, false);
        summary.put("active", false);remote.set(detail(id, "已完成的消息", true));before = requests.get();repository.refresh(id, summary, false);
        check(requests.get() == before + 1 && !ChatData.running(repository.cached(id).response), "task completion updates partial cached response even within same timestamp");
        HistoryRepository.Snapshot old = repository.cached(id);
        remote.set(new JSONObject().put("id", id));failed = false;
        try { repository.refresh(id, summary, true); } catch (Exception expected) { failed = true; }
        check(failed && repository.cached(id).response.toString().equals(old.response.toString()), "invalid response cannot replace saved messages");
        check(!HistoryRepository.needsRefresh(new HistoryRepository.Snapshot(saved, "", System.currentTimeMillis()), new JSONObject()), "missing revision does not trigger full reload on every poll");
        check(HistoryRepository.needsRefresh(new HistoryRepository.Snapshot(saved, "", 1), new JSONObject()), "missing revision eventually revalidates");
        StringBuilder huge=new StringBuilder();while(huge.length()<1500000)huge.append("大段消息与命令输出😀\n");
        JSONObject large=detail("large",huge.toString(),true);HistoryStore disk=new HistoryStore(context,server,email);disk.putChat("large",large,"1",1);disk.close();
        disk=new HistoryStore(context,server,email);check(ChatData.messages(disk.chat("large").response).get(1).getString("content").equals(huge.toString()),"multi-megabyte Unicode history survives CursorWindow and chunk boundaries");disk.close();
        HistoryRepository otherAccount = new HistoryRepository(context, server, "other@example.invalid", source);
        check(otherAccount.list().length() == 0 && otherAccount.cached(id) == null, "accounts cannot read each other's history");otherAccount.close(true);
        HistoryRepository otherServer = new HistoryRepository(context, "https://other.example.invalid", email, source);
        check(otherServer.cached(id) == null, "servers cannot read each other's history");otherServer.close(true);
        repository.close(true);repository = new HistoryRepository(context, server, email, source);
        check(repository.list().length() == 0 && repository.cached(id) == null, "explicit logout clears local history");repository.close(true);

        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);AtomicReference<Throwable> lateError = new AtomicReference<>();
        HistoryRepository delayed = new HistoryRepository(context, server, email, chat -> { entered.countDown();if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");return detail(id, "late response", true); });
        Thread worker = new Thread(() -> { try { delayed.refresh(id, summary, true); } catch (Throwable e) { lateError.set(e); } });
        worker.start();
        try { check(entered.await(5, TimeUnit.SECONDS), "delayed request started");delayed.close(true); }
        finally { release.countDown(); }
        worker.join(5000);check(!worker.isAlive() && lateError.get() instanceof InterruptedException, "logout prevents late HTTP response from restoring history");
        repository = new HistoryRepository(context, server, email, source);check(repository.cached(id) == null, "logout stays cleared after in-flight response");repository.close(true);
    }
}
