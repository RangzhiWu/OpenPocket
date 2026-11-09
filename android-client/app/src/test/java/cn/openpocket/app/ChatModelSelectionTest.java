package cn.openpocket.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChatModelSelectionTest {
    private JSONObject detail(String id,String model)throws Exception{return new JSONObject().put("id",id).put("chat",new JSONObject().put("models",new JSONArray().put(model)));}
    @Test public void conversationsRestoreTheirOwnModelsAndNewChatsUseDefault()throws Exception{
        ChatModelSelection models=new ChatModelSelection(new JSONObject());
        models.observe("a",detail("a","server-task-assistant"),0);models.observe("b",detail("b","server-codex-agent"),0);
        models.choose("a","server-claude-agent");
        assertEquals("server-claude-agent",models.selected("a"));assertEquals("server-codex-agent",models.selected("b"));assertEquals(ChatModelSelection.DEFAULT,models.selected("new"));
        assertFalse(models.observe("b",detail("a","wrong"),models.revision("b")));assertEquals("server-codex-agent",models.selected("b"));
    }
    @Test public void unsentAndOfflineSelectionSurvivesReopeningWithoutChangingTerminal()throws Exception{
        JSONObject prefs=new JSONObject().put("chat:a",new JSONObject().put("terminal","terminal-a").put("directory","/srv/a"));ChatModelSelection models=new ChatModelSelection(prefs);
        models.choose("a","server-task-assistant");
        ChatModelSelection reopened=new ChatModelSelection(new JSONObject(prefs.toString()));
        assertEquals("server-task-assistant",reopened.selected("a"));assertNotNull(reopened.pending("a"));
        assertFalse(reopened.observe("a",detail("a","server-codex-agent"),0));
        assertEquals("terminal-a",prefs.getJSONObject("chat:a").getString("terminal"));assertEquals("/srv/a",prefs.getJSONObject("chat:a").getString("directory"));
    }
    @Test public void delayedSnapshotCannotUndoSelectionEvenAfterSaveCompletes()throws Exception{
        ChatModelSelection models=new ChatModelSelection(new JSONObject());long before=models.revision("a");
        ChatModelSelection.Change choice=models.choose("a","server-task-assistant");long during=models.revision("a");
        assertFalse(models.observe("a",detail("a","server-codex-agent"),during));assertTrue(models.confirm(choice));
        assertFalse(models.observe("a",detail("a","server-codex-agent"),before));assertFalse(models.observe("a",detail("a","server-codex-agent"),during));assertEquals("server-task-assistant",models.selected("a"));
    }
    @Test public void rapidChangesAndLateAcknowledgementsKeepLatestChoice()throws Exception{
        ChatModelSelection models=new ChatModelSelection(new JSONObject());
        ChatModelSelection.Change first=models.choose("a","x"),second=models.choose("a","y"),third=models.choose("a","x");
        assertFalse(models.confirm(first));assertFalse(models.confirm(second));assertNotNull(models.pending("a"));assertTrue(models.confirm(third));assertNull(models.pending("a"));
    }
    @Test public void freshRemoteChangeIsAcceptedForThatConversationAfterLocalSave()throws Exception{
        ChatModelSelection models=new ChatModelSelection(new JSONObject());models.confirm(models.choose("a","local"));
        assertTrue(models.observe("a",detail("a","remote"),models.revision("a")));assertEquals("remote",models.selected("a"));assertEquals(ChatModelSelection.DEFAULT,models.selected("b"));
    }
    @Test public void switchingConversationAndSubmittingUsesItsOwnModel()throws Exception{
        ChatModelSelection models=new ChatModelSelection(new JSONObject());models.choose("a","server-task-assistant");models.choose("b","server-codex-agent");
        for(String id:new String[]{"a","b","a","b"}){String model=models.selected(id);JSONObject request=CompletionRequest.build(detail(id,model),id,model,"socket","hello",new JSONArray(),"");assertEquals(id.equals("a")?"server-task-assistant":"server-codex-agent",request.getString("model"));assertEquals(model,request.getJSONObject("user_message").getJSONArray("models").getString(0));}
    }
    @Test public void modelPatchContainsOnlyConversationModelMetadata()throws Exception{
        JSONObject body=ChatModelSelection.patch("server-task-assistant");assertEquals(1,body.length());JSONObject chat=body.getJSONObject("chat");assertEquals(1,chat.length());assertEquals("server-task-assistant",chat.getJSONArray("models").getString(0));
    }
}
