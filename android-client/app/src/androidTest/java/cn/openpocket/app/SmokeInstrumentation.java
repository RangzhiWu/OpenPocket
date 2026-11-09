package cn.openpocket.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Runs on the isolated emulator. No server credentials are built into either APK. */
public final class SmokeInstrumentation extends Instrumentation {
    Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments=args;start(); }
    private void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if(arguments!=null&&"true".equals(arguments.getString("offlineUsage"))){OfflineUsageChecks.run(this);result.putString("stream","PASS: multiple offline chats and persisted usage reopen while all network workers block; usage refresh isolated and coalesced; failed prefetch does not block another chat\n");finish(Activity.RESULT_OK,result);return;}
            if(arguments!=null&&"true".equals(arguments.getString("chatModels"))){ChatModelChecks.run(this);result.putString("stream","PASS: per-chat model choices, A/B switching, rerender, offline persistence, SQLite model updates preserve other chats and history\n");finish(Activity.RESULT_OK,result);return;}
            if(arguments!=null&&"true".equals(arguments.getString("keyboard"))){TerminalKeyboardChecks.run(this);result.putString("stream","PASS: docked keyboard offline Chinese, Ctrl/Alt bytes, shortcuts, draft focus, no system IME connection, output lift and restore, Back\n");finish(Activity.RESULT_OK,result);return;}
            check(Api.validateServer("http://127.0.0.1:3000/").equals("http://127.0.0.1:3000"),"server normalization");
            boolean rejected=false;try{Api.validateServer("http://example.com");}catch(Exception e){rejected=true;}check(rejected,"HTTP disallowed outside configured host");
            JSONObject sample=new JSONObject("{\"chat\":{\"history\":{\"currentId\":\"a2\",\"messages\":{\"u1\":{\"id\":\"u1\",\"role\":\"user\"},\"a1\":{\"id\":\"a1\",\"parentId\":\"u1\",\"role\":\"assistant\"},\"a2\":{\"id\":\"a2\",\"parentId\":\"u1\",\"role\":\"assistant\",\"done\":false}}}}}");
            List<JSONObject> chain=ChatData.messages(sample);check(chain.size()==2&&chain.get(1).optString("id").equals("a2"),"selected branch excludes sibling");check(ChatData.running(sample),"running state");
            HistoryCacheChecks.run(getTargetContext());
            result.putString("history_cache","PASS: durable SQLite snapshots, offline read, revision request counts, changed messages, tool output, task completion, account isolation, logout and in-flight requests");
            SecureStore testStore=new SecureStore(getTargetContext(),"instrumentation-session","openpocket.instrumentation.session");testStore.saveToken("roundtrip-test");check(testStore.token().equals("roundtrip-test"),"Keystore encryption roundtrip");testStore.clear();check(testStore.token().isEmpty(),"logout removes token");
            SecureStore store=new SecureStore(getTargetContext());
            if(arguments!=null&&"true".equals(arguments.getString("cacheUi"))){
                String server="http://127.0.0.1:3000",email="offline-ui@example.invalid";
                HistoryRepository fixture=new HistoryRepository(getTargetContext(),server,email,id->HistoryCacheChecks.detail(id,"LOCAL_HISTORY_TEST 离线保存成功",true));
                JSONArray list=new JSONArray().put(new JSONObject().put("id","offline-one").put("title","离线会话一").put("updated_at",100))
                    .put(new JSONObject().put("id","offline-two").put("title","离线会话二").put("updated_at",100));
                fixture.saveList(list);fixture.saveModels(new JSONArray().put(new JSONObject().put("id","test-model").put("name","测试模型")));
                for(int i=0;i<list.length();i++)fixture.refresh(list.getJSONObject(i).getString("id"),list.getJSONObject(i),false);
                fixture.close(false);store.server(server);store.email(email);store.saveToken("offline-ui-test-token");store.lastChat("offline-one");
                if("true".equals(arguments.getString("expired")))store.expired(true);
                Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                long deadline=System.currentTimeMillis()+8000;boolean shown=false;
                while(System.currentTimeMillis()<deadline){java.util.concurrent.atomic.AtomicBoolean found=new java.util.concurrent.atomic.AtomicBoolean();runOnMainSync(()->found.set(containsText(activity.getWindow().getDecorView(),"LOCAL_HISTORY_TEST")));if(found.get()){shown=true;break;}Thread.sleep(100);}
                check(shown,"offline UI renders saved message without waiting for server");result.putString("offline_ui","PASS: saved messages render before any server response");
            }
            String seed=arguments==null?null:arguments.getString("seed");
            if(seed!=null){
                JSONObject session=new JSONObject(new String(Files.readAllBytes(Paths.get(seed)),java.nio.charset.StandardCharsets.UTF_8));
                Api api=new Api(Api.validateServer(session.getString("server")),session.getString("token"));
                JSONArray chats=api.list("/api/v1/chats/?include_folders=true&include_pinned=true");check(chats.length()>0,"real chat list");
                JSONObject models=api.get("/api/models");check(models.getJSONArray("data").length()>0,"real model list");
                JSONObject usage=api.get("/api/v1/users/usage?days=30");check(usage.getJSONObject("totals").getLong("lifetime_tokens")>0,"real token usage");
                JSONObject detail=api.get("/api/v1/chats/"+chats.getJSONObject(0).getString("id"));check(!ChatData.messages(detail).isEmpty(),"real selected message chain");
                if("true".equals(arguments.getString("sendCheck"))) {
                    LiveSession live=new LiveSession(api,changed->{});live.connect();
                    long connectDeadline=System.currentTimeMillis()+20000;
                    while(!live.connected()&&System.currentTimeMillis()<connectDeadline)Thread.sleep(100);
                    check(live.connected(),"authenticated realtime session connected");
                    JSONObject blank=new JSONObject().put("title","OpenPocket 接入验证（自动清理）").put("models",new JSONArray().put("server-codex-agent"))
                        .put("history",new JSONObject().put("currentId",JSONObject.NULL).put("messages",new JSONObject())).put("messages",new JSONArray());
                    String id=api.post("/api/v1/chats/new",new JSONObject().put("chat",blank)).getString("id");
                    try {
                        String uid=java.util.UUID.randomUUID().toString(),aid=java.util.UUID.randomUUID().toString();
                        String prompt="Reply with exactly OPENPOCKET_OK. Do not use tools.";
                        JSONObject user=new JSONObject().put("id",uid).put("role","user").put("content",prompt).put("parentId",JSONObject.NULL)
                            .put("childrenIds",new JSONArray().put(aid)).put("timestamp",System.currentTimeMillis()/1000);
                        JSONObject sent=api.post("/api/chat/completions",new JSONObject().put("chat_id",id).put("id",aid).put("model","server-codex-agent")
                            .put("stream",true).put("session_id",live.id()).put("parent_id",JSONObject.NULL).put("user_message",user)
                            .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",prompt))).put("background_tasks",new JSONObject()));
                        check(sent.getJSONArray("task_ids").length()>0,"generation detaches to server task");
                        api.get("/api/tasks/chat/"+id);
                        boolean complete=false;long deadline=System.currentTimeMillis()+90000;
                        while(System.currentTimeMillis()<deadline){
                            JSONObject state=api.get("/api/v1/chats/"+id);List<JSONObject> turn=ChatData.messages(state);
                            if(turn.size()>=2&&!ChatData.running(state)) {
                                JSONObject answer=turn.get(turn.size()-1);String text=ChatData.text(answer.opt("content"));
                                JSONArray output=answer.optJSONArray("output");
                                if(output!=null)for(int i=0;i<output.length();i++){JSONObject o=output.optJSONObject(i);if(o!=null&&o.optString("type").equals("message"))text+=ChatData.text(o.opt("content"));}
                                check(text.contains("OPENPOCKET_OK"),"completed answer persists for another client");complete=true;break;
                            }
                            Thread.sleep(750);
                        }
                        check(complete,"server task completed within timeout");result.putString("send_check","PASS: new chat, detached task, persisted reply");
                    } finally { api.request("DELETE","/api/v1/chats/"+id,null);live.close(); }
                }
                store.server(api.base);store.email(session.optString("email"));store.saveToken(api.token);
                result.putInt("real_chats",chats.length());result.putLong("real_tokens",usage.getJSONObject("totals").getLong("lifetime_tokens"));
                Intent intent=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivitySync(intent);waitForIdleSync();
            }
            result.putString("stream","PASS: server policy, branch selection, task state, Keystore; live API checks when seeded\n");
            finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","FAIL: "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");finish(Activity.RESULT_CANCELED,result);}
    }
    private boolean containsText(android.view.View view,String text){
        if(view instanceof android.widget.TextView&&((android.widget.TextView)view).getText().toString().contains(text))return true;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(containsText(group.getChildAt(i),text))return true;}
        return false;
    }
}
