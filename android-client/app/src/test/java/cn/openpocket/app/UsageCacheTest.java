package cn.openpocket.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class UsageCacheTest {
    static final class Fixture implements UsageCache.Storage,UsageCache.Source,UsageCache.Clock{
        JSONObject disk=new JSONObject();long time=100000;int requests;boolean offline;
        public JSONObject read(){return disk;}public void write(JSONObject data)throws Exception{disk=new JSONObject(data.toString());}
        public long now(){return time;}
        public JSONObject fetch()throws Exception{requests++;if(offline)throw new java.io.IOException("offline");return new JSONObject().put("totals",new JSONObject().put("lifetime_tokens",1234));}
        UsageCache open()throws Exception{UsageCache cache=new UsageCache(this,this,this);cache.load();return cache;}
    }
    @Test public void repeatedNavigationAndPollsReuseTotalsForOneMinute()throws Exception{
        Fixture fixture=new Fixture();UsageCache cache=fixture.open();assertTrue(cache.begin(false));assertFalse(cache.begin(false));cache.fetch();
        for(int i=0;i<24;i++){fixture.time+=2500;assertEquals(i==23,cache.begin(false));}
        cache.fetch();assertEquals(2,fixture.requests);
    }
    @Test public void reopeningReadsSavedTotalsBeforeNetwork()throws Exception{
        Fixture fixture=new Fixture();UsageCache cache=fixture.open();cache.begin(false);cache.fetch();fixture.offline=true;
        UsageCache reopened=fixture.open();assertEquals(1234,reopened.value().getJSONObject("totals").getLong("lifetime_tokens"));assertFalse(reopened.begin(false));assertEquals(1,fixture.requests);
    }
    @Test public void offlineFailureKeepsSavedStatisticsAndBacksOff()throws Exception{
        Fixture fixture=new Fixture();UsageCache cache=fixture.open();cache.begin(false);cache.fetch();fixture.time+=60000;fixture.offline=true;assertTrue(cache.begin(false));try{cache.fetch();fail();}catch(java.io.IOException expected){}
        assertEquals(1234,cache.value().getJSONObject("totals").getLong("lifetime_tokens"));assertFalse(cache.begin(false));fixture.time+=10000;assertTrue(cache.begin(false));
    }
    @Test public void manualRefreshBypassesAgeAndStillCoalescesInFlightRequests()throws Exception{
        Fixture fixture=new Fixture();UsageCache cache=fixture.open();cache.begin(false);cache.fetch();assertTrue(cache.begin(true));assertFalse(cache.begin(true));cache.fetch();assertEquals(2,fixture.requests);
    }
}
