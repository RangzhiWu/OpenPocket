package cn.openpocket.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import android.content.ContentResolver;
import android.net.Uri;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

class Api {
    volatile String base;
    volatile String token;
    private String sessionId="";
    Api(String base, String token) { this.base = base; this.token = token; }
    Api session(String id) { Api result=new Api(base,token);result.sessionId=id;return result; }
    String sessionId(){return sessionId;}
    static String validateServer(String input) throws Exception {
        URI uri = new URI(input.trim());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new Exception("请填写完整服务器地址，例如 https://webui.example.com");
        if (!"https".equalsIgnoreCase(uri.getScheme()) &&
            !("http".equalsIgnoreCase(uri.getScheme()) && privateHost(uri.getHost())))
            throw new Exception("公网服务器请使用 HTTPS；HTTP 仅用于可信局域网、回环或私人 VPN 的 IP 地址。");
        if(uri.getPort()==0||uri.getPort()>65535)throw new Exception("服务器端口无效");
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) throw new Exception("服务器地址后请不要附加页面路径");
        return input.trim().replaceAll("/+$", "");
    }
    private static boolean privateHost(String host){
        if("localhost".equalsIgnoreCase(host)||"[::1]".equals(host)||"::1".equals(host))return true;
        String[] parts=host.split("\\.");if(parts.length!=4)return false;int[] bytes=new int[4];
        for(int i=0;i<4;i++){if(!parts[i].matches("0|[1-9][0-9]{0,2}"))return false;bytes[i]=Integer.parseInt(parts[i]);if(bytes[i]>255)return false;}
        return bytes[0]==10||bytes[0]==127||(bytes[0]==172&&bytes[1]>=16&&bytes[1]<=31)||(bytes[0]==192&&bytes[1]==168)||(bytes[0]==100&&bytes[1]>=64&&bytes[1]<=127);
    }
    private HttpURLConnection connection(String method, String path) throws Exception {
        if(!path.startsWith("/") || path.startsWith("//"))throw new IllegalArgumentException("Invalid API path");
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(12000); connection.setReadTimeout(45000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "OpenPocket/"+BuildConfig.VERSION_NAME+" Android");
        if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        if(!sessionId.isEmpty())connection.setRequestProperty("X-Session-Id",sessionId);
        return connection;
    }
    Object request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection=connection(method,path);
        try {
            if (body != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            return response(connection);
        } finally { connection.disconnect(); }
    }
    private Object response(HttpURLConnection connection) throws Exception {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String response = "";
            if (stream != null) try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); if (out.size() > 24 * 1024 * 1024) throw new Exception("响应过大，请在网页版查看此对话"); }
                response = out.toString("UTF-8");
            }
            if (status == 401) throw new AuthException();
            if (status == 404) throw new NotFoundException();
            if (status < 200 || status >= 300) {
                String detail = "请求失败（" + status + "）";
                try { detail = new JSONObject(response).optString("detail", detail); } catch (Exception ignored) { }
                throw new Exception(detail.length() > 300 ? detail.substring(0, 300) : detail);
            }
            return response.isEmpty() ? new JSONObject() : new JSONTokener(response).nextValue();
    }
    interface Progress { void changed(long sent); }
    static String query(String value) { try { return java.net.URLEncoder.encode(value,"UTF-8").replace("+","%20"); } catch(Exception e) { throw new IllegalArgumentException(e); } }
    JSONObject upload(String path, ContentResolver resolver, Uri uri, String name, String mime, long size, Progress progress) throws Exception {
        HttpURLConnection connection=connection("POST",path);connection.setReadTimeout(120000);
        String boundary="OpenPocket"+java.util.UUID.randomUUID().toString().replace("-","");
        String safeName=name.replaceAll("[\\r\\n\\\"\\\\]","_").replace('/','_');
        byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+safeName+"\"\r\nContent-Type: "+mime.replaceAll("[\\r\\n]","")+"\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);connection.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        if(size>=0)connection.setFixedLengthStreamingMode(prefix.length+size+suffix.length);else connection.setChunkedStreamingMode(65536);
        try(InputStream in=resolver.openInputStream(uri)) {
            if(in==null)throw new Exception("无法读取所选文件，请重新选择。");
            try(OutputStream out=connection.getOutputStream()) {
                out.write(prefix);byte[] buffer=new byte[65536];int count;long sent=0,last=0;
                while((count=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();out.write(buffer,0,count);sent+=count;if(progress!=null&&System.currentTimeMillis()-last>200){progress.changed(sent);last=System.currentTimeMillis();}}
                out.write(suffix);if(progress!=null)progress.changed(sent);
            }
            return requireObject(response(connection));
        }finally{connection.disconnect();}
    }
    byte[] bytes(String path,int max) throws Exception {
        HttpURLConnection connection=connection("GET",path);
        try {
            if(connection.getResponseCode()<200||connection.getResponseCode()>=300){response(connection);throw new Exception("无法读取文件");}
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1){out.write(buffer,0,count);if(out.size()>max)throw new Exception("文件太大，请用命令处理或在电脑端编辑。");}return out.toByteArray();
            }
        }finally{connection.disconnect();}
    }
    JSONObject get(String path) throws Exception { return requireObject(request("GET", path, null)); }
    JSONArray list(String path) throws Exception { return (JSONArray) request("GET", path, null); }
    JSONObject post(String path, JSONObject body) throws Exception { return requireObject(request("POST", path, body)); }
    JSONObject renameChat(String id,String title) throws Exception {
        JSONObject result=post("/api/v1/chats/"+query(id),new JSONObject().put("chat",new JSONObject().put("title",title)));
        if(!id.equals(result.optString("id"))||result.optJSONObject("chat")==null)throw new Exception("服务器没有返回更新后的对话，请重试。");
        return result;
    }
    JSONObject selectChatModel(String id,String model) throws Exception {
        JSONObject result=post("/api/v1/chats/"+query(id),ChatModelSelection.patch(model));
        JSONObject chat=result.optJSONObject("chat");JSONArray selected=chat==null?null:chat.optJSONArray("models");
        if(!id.equals(result.optString("id"))||selected==null||!model.equals(selected.optString(0)))throw new Exception("服务器没有保存此对话的模型选择，请重试。");
        return result;
    }
    private JSONObject requireObject(Object result) throws Exception {
        if(result instanceof JSONObject)return (JSONObject)result;
        throw new Exception("服务器未返回预期结果，请刷新对话确认任务状态。");
    }
    static final class AuthException extends Exception { AuthException() { super("登录已过期，请重新登录"); } }
    static final class NotFoundException extends Exception { NotFoundException() { super("请求的内容不存在或已删除"); } }
}
