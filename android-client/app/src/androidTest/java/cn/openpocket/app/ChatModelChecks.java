package cn.openpocket.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.widget.Button;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated offline UI fixture: never uses a real account or calls a model. */
final class ChatModelChecks {
    private static final String SERVER="http://127.0.0.1:3000",EMAIL="model-isolation@example.invalid";
    static void run(Instrumentation runner)throws Exception{
        HistoryCacheChecks.run(runner.getTargetContext());
        HistoryRepository fixture=new HistoryRepository(runner.getTargetContext(),SERVER,EMAIL,id->{JSONObject response=HistoryCacheChecks.detail(id,"仅用于会话配置隔离测试",true);response.getJSONObject("chat").put("models",new JSONArray().put(id.equals("model-a")?"server-task-assistant":"server-codex-agent"));return response;});
        fixture.close(true);fixture=new HistoryRepository(runner.getTargetContext(),SERVER,EMAIL,id->{JSONObject response=HistoryCacheChecks.detail(id,"仅用于会话配置隔离测试",true);response.getJSONObject("chat").put("models",new JSONArray().put(id.equals("model-a")?"server-task-assistant":"server-codex-agent"));return response;});
        JSONArray list=new JSONArray();for(String id:new String[]{"model-a","model-b"}){JSONObject item=new JSONObject().put("id",id).put("title",id).put("updated_at",100);list.put(item);fixture.refresh(id,item,false);}fixture.saveList(list);
        fixture.saveModels(new JSONArray().put(new JSONObject().put("id","server-task-assistant").put("name","任务助手")).put(new JSONObject().put("id","server-codex-agent").put("name","Codex 编程")).put(new JSONObject().put("id","server-claude-agent").put("name","Claude 编程")));fixture.close(false);
        SecureStore store=new SecureStore(runner.getTargetContext());store.server(SERVER);store.email(EMAIL);store.saveToken("isolated-offline-model-test");store.expired(true);store.lastChat("model-a");
        MainActivity activity=(MainActivity)runner.startActivitySync(new Intent(runner.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitLabel(runner,activity,"任务助手");
        call(runner,activity,"selectModel",new Class<?>[]{String.class,String.class},"model-a","server-claude-agent");waitLabel(runner,activity,"Claude 编程");
        call(runner,activity,"renderPage",new Class<?>[0]);waitLabel(runner,activity,"Claude 编程");
        open(runner,activity,"model-b","Codex 编程");open(runner,activity,"model-a","Claude 编程");
        call(runner,activity,"selectModel",new Class<?>[]{String.class,String.class},"model-a","server-task-assistant");waitLabel(runner,activity,"任务助手");
        open(runner,activity,"model-b","Codex 编程");open(runner,activity,"model-a","任务助手");
        delayedSave(runner,activity);
        // Wait for the existing serialized preference writer before reopening storage.
        java.util.concurrent.ExecutorService writes=(java.util.concurrent.ExecutorService)member(activity,"uploads");writes.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
        HistoryRepository reopened=new HistoryRepository(runner.getTargetContext(),SERVER,EMAIL,id->{throw new AssertionError("network access forbidden");});ChatModelSelection saved=new ChatModelSelection(reopened.preferences());
        if(!saved.selected("model-a").equals("server-task-assistant")||!saved.selected("model-b").equals("server-codex-agent"))throw new AssertionError("per-chat UI choices did not persist");reopened.close(false);
        Instrumentation.ActivityMonitor monitor=runner.addMonitor(MainActivity.class.getName(),null,false);
        onMain(runner,activity::recreate);
        android.app.Activity recreated=runner.waitForMonitorWithTimeout(monitor,8000);runner.removeMonitor(monitor);
        if(recreated==null)throw new AssertionError("activity did not recreate");waitLabel(runner,(MainActivity)recreated,"任务助手");
        open(runner,(MainActivity)recreated,"model-b","Codex 编程");open(runner,(MainActivity)recreated,"model-a","任务助手");
    }
    private static void delayedSave(Instrumentation runner,MainActivity activity)throws Exception{
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);java.util.List<String> updates=new java.util.concurrent.CopyOnWriteArrayList<>();
        Api fake=new Api(SERVER,"isolated-api-fixture"){@Override JSONObject selectChatModel(String id,String model)throws Exception{updates.add(id+":"+model);if(updates.size()==1){entered.countDown();if(!release.await(8,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("delayed model update timeout");}return HistoryCacheChecks.detail(id,"metadata save response",true);}};
        onMain(runner,()->{try{set(activity,"api",fake);set(activity,"authenticationExpired",false);}catch(Exception e){throw new RuntimeException(e);}});
        try{
            call(runner,activity,"selectModel",new Class<?>[]{String.class,String.class},"model-a","server-claude-agent");
        }finally{onMain(runner,()->{try{set(activity,"authenticationExpired",true);}catch(Exception e){throw new RuntimeException(e);}});}
        if(!entered.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("model save did not start");
        try{
            open(runner,activity,"model-b","Codex 编程");
            onMain(runner,()->{try{set(activity,"authenticationExpired",false);}catch(Exception e){throw new RuntimeException(e);}});
            call(runner,activity,"selectModel",new Class<?>[]{String.class,String.class},"model-a","server-task-assistant");
        }finally{onMain(runner,()->{try{set(activity,"authenticationExpired",true);}catch(Exception e){throw new RuntimeException(e);}});release.countDown();}
        ((java.util.concurrent.ExecutorService)member(activity,"modelWrites")).submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);runner.waitForIdleSync();
        waitLabel(runner,activity,"Codex 编程");open(runner,activity,"model-a","任务助手");
        if(!updates.equals(java.util.Arrays.asList("model-a:server-claude-agent","model-a:server-task-assistant")))throw new AssertionError("model saves escaped their conversation or reordered");
    }
    private static void open(Instrumentation runner,MainActivity activity,String id,String label)throws Exception{call(runner,activity,"openChat",new Class<?>[]{String.class},id);waitLabel(runner,activity,label);}
    private static void waitLabel(Instrumentation runner,MainActivity activity,String label)throws Exception{long until=System.currentTimeMillis()+8000;boolean[] found={false};while(System.currentTimeMillis()<until){onMain(runner,()->{try{Button button=(Button)member(activity,"modelButton");found[0]=Boolean.TRUE.equals(member(activity,"cacheReady"))&&button!=null&&button.getText().toString().equals(label+" ▾");}catch(Exception e){throw new RuntimeException(e);}});if(found[0])return;Thread.sleep(50);}throw new AssertionError("expected conversation model: "+label);}
    private static Object member(Object target,String name)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static void call(Instrumentation runner,MainActivity activity,String name,Class<?>[] types,Object... values){onMain(runner,()->{try{Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(activity,values);}catch(Exception e){throw new RuntimeException(e);}});}
    private static void onMain(Instrumentation runner,Runnable action){AtomicReference<Throwable> failure=new AtomicReference<>();runner.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new AssertionError("UI check failed",failure.get());}
}
