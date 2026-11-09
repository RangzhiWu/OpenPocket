package cn.openpocket.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercise the real UI while all ordinary network workers are deliberately blocked. */
final class OfflineUsageChecks {
    private static final String SERVER="http://127.0.0.1:3000",EMAIL="offline-usage@example.invalid";
    static void run(Instrumentation runner)throws Exception{
        HistoryRepository repo=repository(runner);repo.close(true);repo=repository(runner);
        JSONArray list=new JSONArray();for(String id:new String[]{"offline-a","offline-b","offline-c"}){JSONObject item=new JSONObject().put("id",id).put("title",id).put("updated_at",100);list.put(item);repo.refresh(id,item,false);}repo.saveList(list);
        repo.usage(new JSONObject().put("savedAt",System.currentTimeMillis()).put("data",data(123456)));repo.close(false);
        SecureStore store=new SecureStore(runner.getTargetContext());store.server(SERVER);store.email(EMAIL);store.saveToken("isolated-offline-fixture");store.expired(true);store.lastChat("offline-a");
        MainActivity activity=(MainActivity)runner.startActivitySync(new Intent(runner.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitChat(runner,activity,"offline-a");
        CountDownLatch occupied=new CountDownLatch(3),release=new CountDownLatch(1);
        ExecutorService network=(ExecutorService)get(activity,"io");for(int i=0;i<3;i++)network.execute(()->{occupied.countDown();try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        if(!occupied.await(3,TimeUnit.SECONDS))throw new AssertionError("failed to occupy network pool");
        try{
            for(String id:new String[]{"offline-b","offline-c","offline-a","offline-c"}){long start=System.currentTimeMillis();invoke(runner,activity,"openChat",new Class<?>[]{String.class},id);waitChat(runner,activity,id);if(System.currentTimeMillis()-start>1500)throw new AssertionError("offline read waited for network workers");}
            invoke(runner,activity,"updateConnectionLabel",new Class<?>[0]);onMain(runner,()->{try{if(!((TextView)get(activity,"connection")).getText().toString().contains("3/3"))throw new AssertionError("saved conversation count missing");}catch(Exception e){throw new RuntimeException(e);}});
            onMain(runner,()->{try{set(activity,"tab","usage");}catch(Exception e){throw new RuntimeException(e);}});invoke(runner,activity,"renderPage",new Class<?>[0]);assertText(runner,activity,"123,456");
            usageRefresh(runner,activity);
        }finally{release.countDown();}
        prefetchFailure(runner,activity);
        // A new Activity reloads both usage and every conversation from SQLite.
        Instrumentation.ActivityMonitor monitor=runner.addMonitor(MainActivity.class.getName(),null,false);onMain(runner,activity::recreate);MainActivity reopened=(MainActivity)runner.waitForMonitorWithTimeout(monitor,8000);runner.removeMonitor(monitor);if(reopened==null)throw new AssertionError("recreate failed");
        long until=System.currentTimeMillis()+5000;while(!Boolean.TRUE.equals(get(reopened,"cacheReady"))&&System.currentTimeMillis()<until)Thread.sleep(50);
        for(String id:new String[]{"offline-b","offline-c","offline-a"}){invoke(runner,reopened,"openChat",new Class<?>[]{String.class},id);waitChat(runner,reopened,id);}
        onMain(runner,()->{try{set(reopened,"tab","usage");}catch(Exception e){throw new RuntimeException(e);}});invoke(runner,reopened,"renderPage",new Class<?>[0]);assertText(runner,reopened,"234,567");
    }
    private static void usageRefresh(Instrumentation runner,MainActivity activity)throws Exception{
        HistoryRepository repo=(HistoryRepository)get(activity,"history");AtomicInteger requests=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        UsageCache stats=new UsageCache(new UsageCache.Storage(){public JSONObject read()throws Exception{return repo.usage();}public void write(JSONObject value)throws Exception{repo.usage(value);}},()->{requests.incrementAndGet();entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("usage timeout");return data(234567);},System::currentTimeMillis);stats.load();
        onMain(runner,()->{try{set(activity,"usageCache",stats);set(activity,"authenticationExpired",false);}catch(Exception e){throw new RuntimeException(e);}});
        try{
            invoke(runner,activity,"refreshUsage",new Class<?>[]{boolean.class},true);if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("usage request queued behind ordinary network");assertText(runner,activity,"123,456");
            for(int i=0;i<3;i++)invoke(runner,activity,"refreshUsage",new Class<?>[]{boolean.class},true);
            invoke(runner,activity,"openChat",new Class<?>[]{String.class},"offline-b");waitChat(runner,activity,"offline-b");
        }finally{onMain(runner,()->{try{set(activity,"authenticationExpired",true);}catch(Exception e){throw new RuntimeException(e);}});release.countDown();}
        ((ExecutorService)get(activity,"usageSync")).submit(()->{}).get(5,TimeUnit.SECONDS);runner.waitForIdleSync();
        onMain(runner,()->{try{set(activity,"tab","usage");set(activity,"authenticationExpired",false);}catch(Exception e){throw new RuntimeException(e);}});invoke(runner,activity,"renderPage",new Class<?>[0]);assertText(runner,activity,"234,567");
        for(int i=0;i<10;i++)invoke(runner,activity,"refreshUsage",new Class<?>[]{boolean.class},false);
        if(requests.get()!=1)throw new AssertionError("fresh usage cache refetched on navigation/poll");
        onMain(runner,()->{try{set(activity,"authenticationExpired",true);}catch(Exception e){throw new RuntimeException(e);}});
    }
    private static void prefetchFailure(Instrumentation runner,MainActivity activity)throws Exception{
        HistoryRepository original=(HistoryRepository)get(activity,"history");
        HistoryRepository test=new HistoryRepository(runner.getTargetContext(),"https://prefetch.example.invalid",EMAIL,id->{if(id.equals("bad"))throw new java.io.IOException("one chat timed out");return HistoryCacheChecks.detail(id,"saved without opening",true);});test.close(true);
        HistoryRepository fixture=new HistoryRepository(runner.getTargetContext(),"https://prefetch.example.invalid",EMAIL,id->{if(id.equals("bad"))throw new java.io.IOException("one chat timed out");return HistoryCacheChecks.detail(id,"saved without opening",true);});
        JSONArray list=new JSONArray().put(new JSONObject().put("id","bad").put("updated_at",100)).put(new JSONObject().put("id","good-unvisited").put("updated_at",100));
        try{
            onMain(runner,()->{try{set(activity,"history",fixture);set(activity,"authenticationExpired",false);}catch(Exception e){throw new RuntimeException(e);}});invoke(runner,activity,"prefetchHistory",new Class<?>[]{JSONArray.class},list);
            ((ExecutorService)get(activity,"historySync")).submit(()->{}).get(5,TimeUnit.SECONDS);runner.waitForIdleSync();if(fixture.cached("good-unvisited")==null)throw new AssertionError("one failed chat blocked saving the next unvisited chat");
        }finally{onMain(runner,()->{try{set(activity,"history",original);set(activity,"authenticationExpired",true);}catch(Exception e){throw new RuntimeException(e);}});fixture.close(true);}
    }
    private static JSONObject data(long tokens)throws Exception{return new JSONObject().put("totals",new JSONObject().put("lifetime_tokens",tokens));}
    private static HistoryRepository repository(Instrumentation runner){return new HistoryRepository(runner.getTargetContext(),SERVER,EMAIL,id->HistoryCacheChecks.detail(id,"OFFLINE_FIXTURE_"+id,true));}
    private static void waitChat(Instrumentation runner,MainActivity activity,String id)throws Exception{long until=System.currentTimeMillis()+4000;boolean[] found={false};while(System.currentTimeMillis()<until){onMain(runner,()->{try{found[0]=((JSONObject)get(activity,"current")).optString("id").equals(id)&&contains(activity.getWindow().getDecorView(),"OFFLINE_FIXTURE_"+id);}catch(Exception e){throw new RuntimeException(e);}});if(found[0])return;Thread.sleep(30);}throw new AssertionError("offline conversation missing: "+id);}
    private static void assertText(Instrumentation runner,MainActivity activity,String text){onMain(runner,()->{if(!contains(activity.getWindow().getDecorView(),text))throw new AssertionError("missing UI text: "+text);});}
    private static boolean contains(android.view.View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),text))return true;}return false;}
    private static Object get(Object target,String name)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static void invoke(Instrumentation runner,MainActivity activity,String name,Class<?>[] types,Object... values){onMain(runner,()->{try{Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(activity,values);}catch(Exception e){throw new RuntimeException(e);}});}
    private static void onMain(Instrumentation runner,Runnable action){AtomicReference<Throwable> failure=new AtomicReference<>();runner.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new AssertionError("UI check failed",failure.get());}
}
