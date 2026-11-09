package cn.openpocket.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SyncAndRequestTest {
    private JSONObject summary(long revision) throws Exception { return new JSONObject().put("updated_at",revision); }
    @Test public void unchangedOrOlderServerMetadataDoesNotReloadHistory() throws Exception {
        HistoryRepository.Stamp snapshot=new HistoryRepository.Stamp("102",1,false);
        assertFalse(HistoryRepository.needsRefresh(snapshot,summary(102)));
        assertFalse(HistoryRepository.needsRefresh(snapshot,summary(101)));
        assertTrue(HistoryRepository.needsRefresh(snapshot,summary(103)));
        assertTrue(HistoryRepository.needsRefresh((HistoryRepository.Stamp)null,summary(102)));
    }
    @Test public void runningTaskAlwaysRechecksIncludingCompletionWithinSameSecond() throws Exception {
        HistoryRepository.Stamp partial=new HistoryRepository.Stamp("102",1,true);
        assertTrue(HistoryRepository.needsRefresh(partial,summary(102).put("active",false)));
        HistoryRepository.Stamp completed=new HistoryRepository.Stamp("102",1,false);
        assertTrue(HistoryRepository.needsRefresh(completed,summary(102).put("active",true)));
    }
    @Test public void serversWithoutRevisionsAreThrottledButEventuallyChecked() throws Exception {
        assertFalse(HistoryRepository.needsRefresh(new HistoryRepository.Stamp("",System.currentTimeMillis(),false),new JSONObject()));
        assertTrue(HistoryRepository.needsRefresh(new HistoryRepository.Stamp("",1,false),new JSONObject()));
    }
    @Test public void ordinaryChatDoesNotOptIntoATerminal() throws Exception {
        JSONObject request=CompletionRequest.build(new JSONObject(),"chat","model","socket","hello",new JSONArray(),"");
        assertFalse(request.has("terminal_id"));assertFalse(request.has("files"));assertTrue(request.isNull("parent_id"));
        assertEquals(request.getString("id"),request.getJSONObject("user_message").getJSONArray("childrenIds").getString(0));
    }
    @Test public void fileAndTerminalReachBothPersistedMessageAndCompletionRequest() throws Exception {
        JSONObject file=new JSONObject().put("id","file-1").put("type","file").put("name","附件.pdf");
        JSONObject request=CompletionRequest.build(new JSONObject(),"chat","model","socket","read this",new JSONArray().put(file),"workspace");
        assertEquals("workspace",request.getString("terminal_id"));
        assertEquals("file-1",request.getJSONObject("user_message").getJSONArray("files").getJSONObject(0).getString("id"));
        assertEquals("file-1",request.getJSONArray("files").getJSONObject(0).getString("id"));
        assertFalse(request.has("auto_approve"));
    }
    @Test public void picturesKeepTheirUploadedFileReference() throws Exception {
        JSONObject image=new JSONObject().put("id","image").put("type","image").put("content_type","image/png").put("url","/api/v1/files/image/content");
        JSONObject request=CompletionRequest.build(new JSONObject(),"chat","model","socket","图片",new JSONArray().put(image),"");
        assertEquals(image.toString(),request.getJSONObject("user_message").getJSONArray("files").getJSONObject(0).toString());
    }
    @Test public void filesystemAttachmentMustMatchChatAndTerminal() throws Exception {
        JSONObject file=new JSONObject().put("id","/srv/test.txt").put("type","filesystem").put("terminal_id","terminal").put("session_id","chat");
        JSONArray files=new JSONArray().put(file);
        CompletionRequest.build(new JSONObject(),"chat","model","socket","read",files,"terminal");
        try{CompletionRequest.build(new JSONObject(),"other","model","socket","read",files,"terminal");fail("cross-chat file accepted");}catch(Exception expected){}
        try{CompletionRequest.build(new JSONObject(),"chat","model","socket","read",files,"other");fail("cross-terminal file accepted");}catch(Exception expected){}
    }
    @Test public void selectedBranchAndPreviousDocumentsArePreservedWithoutDuplication() throws Exception {
        JSONObject file=new JSONObject().put("id","doc").put("type","file");
        JSONObject user=new JSONObject().put("id","u").put("role","user").put("content","question").put("files",new JSONArray().put(file));
        JSONObject answer=new JSONObject().put("id","a").put("parentId","u").put("role","assistant").put("content","selected");
        JSONObject sibling=new JSONObject().put("id","other").put("parentId","u").put("role","assistant").put("content","ignored sibling");
        JSONObject snapshot=new JSONObject().put("chat",new JSONObject().put("history",new JSONObject().put("currentId","a").put("messages",new JSONObject().put("u",user).put("a",answer).put("other",sibling))));
        JSONObject request=CompletionRequest.build(snapshot,"chat","model","socket","next",new JSONArray().put(file),"");
        assertEquals("a",request.getString("parent_id"));assertEquals(3,request.getJSONArray("messages").length());assertEquals(1,request.getJSONArray("files").length());
        assertFalse(request.getJSONArray("messages").toString().contains("ignored sibling"));
    }
}
