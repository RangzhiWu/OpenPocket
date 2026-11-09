package cn.openpocket.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** All access stays behind the authenticated WebUI proxy; no terminal API key on device. */
final class TerminalApi {
    private final Api api;
    TerminalApi(Api api) { this.api=api; }
    JSONArray servers() throws Exception { return api.list("/api/v1/terminals/"); }
    String path(String id,String suffix) { return "/api/v1/terminals/"+Api.query(id)+suffix; }
    JSONObject proxy(String id,String method,String suffix,JSONObject data) throws Exception {
        Object response=api.request(method,path(id,suffix),data);
        if(!(response instanceof JSONObject))throw new Exception("终端返回了无法识别的数据。");
        return (JSONObject)response;
    }
    JSONObject files(String id,String directory) throws Exception { return proxy(id,"GET","/files/list?directory="+Api.query(directory),null); }
    JSONObject cwd(String id) throws Exception { return proxy(id,"GET","/files/cwd",null); }
    JSONObject cwd(String id,String directory) throws Exception { return proxy(id,"POST","/files/cwd",new JSONObject().put("path",directory)); }
    String read(String id,String file) throws Exception {
        byte[] bytes=api.bytes(path(id,"/files/view?path="+Api.query(file)),1024*1024);
        for(byte b:bytes)if(b==0)throw new Exception("这是二进制文件，请在电脑端打开或用命令处理。");
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(Exception e){throw new Exception("文件不是 UTF-8 文本，请在电脑端编辑。");}
    }
    JSONObject write(String id,String file,String text) throws Exception { return api.post(path(id,"/files/write"),new JSONObject().put("path",file).put("content",text)); }
    JSONObject execute(String id,String directory,String command) throws Exception {
        return proxy(id,"POST","/execute?wait=0",new JSONObject().put("command",command).put("cwd",directory));
    }
    JSONObject openShell(String id,String directory,String prompt) throws Exception {
        JSONObject env=new JSONObject().put("PS1",prompt).put("PS2","").put("PROMPT_COMMAND","").put("SHELL","/bin/bash").put("TERM","xterm").put("INPUTRC","/dev/null");
        return proxy(id,"POST","/execute?wait=0",new JSONObject().put("command",TerminalShell.startCommand()).put("cwd",directory).put("env",env));
    }
    JSONObject clear(String id,String directory) throws Exception {
        return proxy(id,"POST","/execute?wait=5",new JSONObject().put("command","clear").put("cwd",directory).put("env",new JSONObject().put("TERM","xterm")));
    }
    String[] complete(String id,String directory,TerminalCompletion.Request request) throws Exception {
        JSONObject result=proxy(id,"POST","/execute?wait=5",new JSONObject().put("command",TerminalCompletion.command(request)).put("cwd",directory));
        if(result.optInt("exit_code",-1)!=0)throw new Exception("补全查询失败，请重试。");
        StringBuilder output=new StringBuilder();JSONArray entries=result.optJSONArray("output");if(entries!=null)for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);if(entry!=null)output.append(entry.optString("data"));}
        java.util.LinkedHashSet<String> candidates=new java.util.LinkedHashSet<>();for(String candidate:output.toString().replace("\r","").split("\n"))if(!candidate.isEmpty())candidates.add(candidate);
        return candidates.toArray(new String[0]);
    }
    JSONObject command(String id,String pid,int offset) throws Exception { return proxy(id,"GET","/execute/"+Api.query(pid)+"/status?wait=0&offset="+offset,null); }
    JSONObject input(String id,String pid,String text) throws Exception { return proxy(id,"POST","/execute/"+Api.query(pid)+"/input",new JSONObject().put("input",text.replace("\\","\\\\"))); }
    void resize(String id,String token,int columns,int rows) throws Exception {
        if(!token.matches("[a-f0-9]{32}")||columns<2||columns>500||rows<2||rows>300)throw new IllegalArgumentException("Invalid terminal dimensions");
        String command="sudo -n /usr/bin/python3 /usr/local/lib/openpocket/resize-terminal.py "+token+" "+columns+" "+rows;
        JSONObject result=proxy(id,"POST","/execute?wait=5",new JSONObject().put("command",command));
        if(!"done".equals(result.optString("status"))||result.optInt("exit_code",-1)!=0)throw new Exception("终端尺寸同步失败，请重新打开工作区。");
    }
    JSONObject stop(String id,String pid) throws Exception { return proxy(id,"DELETE","/execute/"+Api.query(pid),null); }
    static String join(String directory,String name) { return directory.replaceAll("/+$","")+"/"+name; }
    static String parent(String path) { int end=path.replaceAll("/+$","").lastIndexOf('/');return end<=0?"/":path.substring(0,end); }
    static String shellQuote(String text) { return "'"+text.replace("'","'\"'\"'")+"'"; }
}
