package cn.openpocket.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class CompletionRequest {
    static JSONObject build(JSONObject snapshot,String chatId,String model,String sessionId,String text,JSONArray files,String terminalId) throws Exception {
        List<JSONObject> chain=ChatData.messages(snapshot);String parent=chain.isEmpty()?"":chain.get(chain.size()-1).optString("id");
        String userId=UUID.randomUUID().toString(),assistantId=UUID.randomUUID().toString();
        for(int i=0;i<files.length();i++){
            JSONObject file=files.getJSONObject(i);
            if("filesystem".equals(file.optString("type"))&&(!terminalId.equals(file.optString("terminal_id"))||!chatId.equals(file.optString("session_id"))))
                throw new Exception("工作区附件属于另一个对话或终端，请重新添加。");
        }
        JSONObject user=new JSONObject().put("id",userId).put("parentId",parent.isEmpty()?JSONObject.NULL:parent).put("childrenIds",new JSONArray().put(assistantId))
            .put("role","user").put("content",text).put("timestamp",System.currentTimeMillis()/1000).put("models",new JSONArray().put(model));
        if(files.length()>0)user.put("files",files);
        JSONArray messages=ChatData.prompt(snapshot);messages.put(new JSONObject().put("role","user").put("content",text));
        JSONObject request=new JSONObject().put("model",model).put("stream",true).put("chat_id",chatId).put("session_id",sessionId).put("id",assistantId)
            .put("parent_id",parent.isEmpty()?JSONObject.NULL:parent).put("user_message",user).put("messages",messages).put("background_tasks",new JSONObject());
        JSONArray context=new JSONArray();Set<String> seen=new HashSet<>();
        for(JSONObject message:chain){JSONArray previous=message.optJSONArray("files");if(previous==null)continue;for(int i=0;i<previous.length();i++){JSONObject file=previous.optJSONObject(i);if(file!=null&&"file".equals(file.optString("type"))&&seen.add(file.optString("type")+":"+file.optString("id")))context.put(file);}}
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);if(seen.add(file.optString("type")+":"+file.optString("id")))context.put(file);}
        if(context.length()>0)request.put("files",context);
        if(!terminalId.isEmpty())request.put("terminal_id",terminalId);
        return request;
    }
}
