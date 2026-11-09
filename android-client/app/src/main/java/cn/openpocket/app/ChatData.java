package cn.openpocket.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Walk the selected parent chain; siblings from edits stay in their own branch. */
final class ChatData {
    static JSONObject object(JSONObject value, String name) { JSONObject result = value == null ? null : value.optJSONObject(name); return result == null ? new JSONObject() : result; }
    static List<JSONObject> messages(JSONObject response) {
        JSONObject chat = object(response, "chat"), history = object(chat, "history"), map = object(history, "messages");
        String current = history.optString("currentId", "");
        if (current.isEmpty() || current.equals("null")) current = response.optString("current_message_id", "");
        List<JSONObject> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        while (!current.isEmpty() && !current.equals("null") && seen.add(current)) {
            JSONObject message = map.optJSONObject(current); if (message == null) break;
            result.add(message); current = message.optString("parentId", "");
        }
        Collections.reverse(result);
        if (result.isEmpty()) {
            JSONArray flat = chat.optJSONArray("messages");
            if (flat != null) for (int i = 0; i < flat.length(); i++) if (flat.optJSONObject(i) != null) result.add(flat.optJSONObject(i));
        }
        return result;
    }
    static String text(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof JSONArray) {
            StringBuilder out = new StringBuilder(); JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                JSONObject part = array.optJSONObject(i);
                if (part != null && part.has("text")) out.append(part.optString("text"));
            }
            return out.toString();
        }
        return value.toString();
    }
    static boolean running(JSONObject response) {
        List<JSONObject> messages = messages(response);
        return !messages.isEmpty() && "assistant".equals(messages.get(messages.size()-1).optString("role")) &&
            !messages.get(messages.size()-1).optBoolean("done", true);
    }
    static JSONArray prompt(JSONObject response) throws Exception {
        JSONArray result = new JSONArray();
        for (JSONObject message : messages(response)) {
            String role = message.optString("role");
            if (role.equals("user") || role.equals("assistant") || role.equals("system")) {
                String content = text(message.opt("content"));
                if (content.isEmpty()) {
                    JSONArray output = message.optJSONArray("output");
                    if (output != null) for (int i=0;i<output.length();i++) {
                        JSONObject item=output.optJSONObject(i);
                        if (item!=null && item.optString("type").equals("message")) content+=text(item.opt("content"));
                    }
                }
                if (!content.isEmpty()) result.put(new JSONObject().put("role", role).put("content", content));
            }
        }
        return result;
    }
}
