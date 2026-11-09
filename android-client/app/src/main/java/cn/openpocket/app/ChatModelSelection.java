package cn.openpocket.app;

import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Conversation-scoped model choices; pending edits win over delayed snapshots. */
final class ChatModelSelection {
    static final String DEFAULT="server-codex-agent";
    static final class Change {
        final String chatId,model;
        final long revision;
        Change(String chatId,String model,long revision){this.chatId=chatId;this.model=model;this.revision=revision;}
    }
    private final JSONObject preferences;
    private final HashMap<String,Long> revisions=new HashMap<>();
    ChatModelSelection(JSONObject preferences){this.preferences=preferences;}
    String selected(String id){JSONObject entry=preferences.optJSONObject("chat:"+id);String model=entry==null?"":entry.optString("model");return model.isEmpty()?DEFAULT:model;}
    long revision(String id){return revisions.getOrDefault(id,0L);}
    private JSONObject entry(String id)throws Exception{
        if(id.isEmpty())throw new IllegalArgumentException("Missing conversation ID");
        JSONObject entry=preferences.optJSONObject("chat:"+id);if(entry==null){entry=new JSONObject();preferences.put("chat:"+id,entry);}return entry;
    }
    Change choose(String id,String model)throws Exception{
        if(model.isEmpty())throw new IllegalArgumentException("Missing model ID");
        entry(id).put("model",model).put("modelPending",true);long next=revision(id)+1;revisions.put(id,next);return new Change(id,model,next);
    }
    Change pending(String id){JSONObject entry=preferences.optJSONObject("chat:"+id);return entry!=null&&entry.optBoolean("modelPending")?new Change(id,selected(id),revision(id)):null;}
    boolean confirm(Change change)throws Exception{
        if(revision(change.chatId)!=change.revision||!selected(change.chatId).equals(change.model))return false;
        entry(change.chatId).remove("modelPending");revisions.put(change.chatId,change.revision+1);return true;
    }
    boolean observe(String id,JSONObject response,long requestedRevision)throws Exception{
        if(id.isEmpty()||!id.equals(response.optString("id"))||revision(id)!=requestedRevision||pending(id)!=null)return false;
        JSONObject chat=response.optJSONObject("chat");JSONArray models=chat==null?null:chat.optJSONArray("models");String model=models==null?"":models.optString(0,"");
        if(model.isEmpty()||model.equals(selected(id)))return false;
        entry(id).put("model",model);revisions.put(id,requestedRevision+1);return true;
    }
    static JSONObject patch(String model)throws Exception{return new JSONObject().put("chat",new JSONObject().put("models",new JSONArray().put(model)));}
}
