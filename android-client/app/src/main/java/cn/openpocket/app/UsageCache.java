package cn.openpocket.app;

import org.json.JSONObject;

/** Persisted server totals shown immediately, with one refresh per minute. */
final class UsageCache {
    interface Storage { JSONObject read()throws Exception;void write(JSONObject value)throws Exception; }
    interface Source { JSONObject fetch()throws Exception; }
    interface Clock { long now(); }
    private final Storage storage;
    private final Source source;
    private final Clock clock;
    private JSONObject value=new JSONObject();
    private long savedAt,retryAt;
    private boolean fetching;
    UsageCache(Storage storage,Source source,Clock clock){this.storage=storage;this.source=source;this.clock=clock;}
    synchronized void load()throws Exception{JSONObject saved=storage.read();JSONObject data=saved.optJSONObject("data");if(data!=null&&data.optJSONObject("totals")!=null){value=data;savedAt=saved.optLong("savedAt");}}
    synchronized JSONObject value(){return value;}
    synchronized long savedAt(){return savedAt;}
    synchronized boolean begin(boolean force){long now=clock.now();if(fetching||(!force&&(now<retryAt||(savedAt>0&&now>=savedAt&&now-savedAt<60000))))return false;fetching=true;return true;}
    JSONObject fetch()throws Exception{
        try{JSONObject fresh=source.fetch();if(fresh.optJSONObject("totals")==null)throw new Exception("用量统计响应不完整");long now=clock.now();storage.write(new JSONObject().put("savedAt",now).put("data",fresh));synchronized(this){value=fresh;savedAt=now;retryAt=0;}return fresh;}
        catch(Exception e){synchronized(this){retryAt=clock.now()+10000;}throw e;}
        finally{synchronized(this){fetching=false;}}
    }
}
