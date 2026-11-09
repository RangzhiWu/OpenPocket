package cn.openpocket.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.function.Consumer;

/** Owns a persistent PTY process and its read offset, independently of the workspace view. */
final class TerminalSession {
    // An exited application may leave the alternate buffer or raw terminal modes active.
    private static final String NORMAL_SCREEN="\u001b[?1049l\u001b[!p\u001b[0m\u001b[?25h";
    static final class Snapshot {
        final String output,directory,process,screen;
        final long start,end,screenOffset;
        final int screenColumns,screenRows;
        final boolean busy,alive;
        Snapshot(String output,String directory,String process,boolean busy,boolean alive,long start,String screen,long screenOffset,int screenColumns,int screenRows){this.output=output;this.directory=directory;this.process=process;this.busy=busy;this.alive=alive;this.start=start;this.end=start+output.length();this.screen=screen;this.screenOffset=screenOffset;this.screenColumns=screenColumns;this.screenRows=screenRows;}
    }
    interface Connection {
        JSONObject open(String directory,String prompt) throws Exception;
        JSONObject read(String process,int offset) throws Exception;
        void input(String process,String text) throws Exception;
        default void resize(String token,int columns,int rows) throws Exception {}
    }
    private final Connection connection;
    private final Consumer<String> persist;
    private TerminalShell shell;
    private String process="",directory,output="",lastSaved="",screen="";
    private long start,screenOffset;
    private int offset;
    private int screenColumns,screenRows,shellRevision;
    private int publishedOffset=-1,publishedRevision=-1;
    private String publishedPending="";
    private boolean busy,alive,interactive;
    private volatile Snapshot snapshot;
    TerminalSession(TerminalApi api,String terminal,String directory,String saved,Consumer<String> persist){
        this(new Connection(){
            public JSONObject open(String cwd,String prompt) throws Exception{return api.openShell(terminal,cwd,prompt);}
            public JSONObject read(String process,int offset) throws Exception{return api.command(terminal,process,offset);}
            public void input(String process,String text) throws Exception{api.input(terminal,process,text);}
            public void resize(String token,int columns,int rows) throws Exception{api.resize(terminal,token,columns,rows);}
        },directory,saved,persist);
    }
    TerminalSession(Connection connection,String directory,String saved,Consumer<String> persist){
        this.connection=connection;this.directory=directory;this.persist=persist;this.shell=new TerminalShell();
        try{if(!saved.isEmpty()){
            JSONObject value=new JSONObject(saved);shell=new TerminalShell(value.getString("token"));process=value.getString("process");offset=value.optInt("offset");shell.pending=value.optString("pending");this.directory=value.optString("directory",directory);output=value.optString("output");busy=value.optBoolean("busy");alive=value.optBoolean("alive");interactive=value.optBoolean("interactive");start=value.optLong("start");screen=value.optString("screen");screenOffset=value.optLong("screenOffset");
            screenColumns=value.optInt("screenColumns");screenRows=value.optInt("screenRows");shellRevision=value.optInt("shellRevision");
            if(!value.has("format"))output=output.replace("\n","\r\n");
        }}catch(Exception ignored){process="";offset=0;alive=false;busy=false;shell=new TerminalShell();}
        publish();
    }
    Snapshot snapshot(){return snapshot;}
    synchronized Snapshot read() throws Exception {
        if(!process.isEmpty()){
            try{accept(connection.read(process,offset));}
            catch(Api.NotFoundException missing){ended("会话已不存在");}
        }
        prepareIfIdle();publish();return snapshot;
    }
    private void ensureOpen() throws Exception {
        if(!process.isEmpty()&&alive)read();
        if(!alive){
            boolean replacing=!process.isEmpty();shell=new TerminalShell();offset=0;process="";busy=true;interactive=true;shellRevision=2;
            if(replacing)output+=NORMAL_SCREEN+"\r\n\u001b[2K[已新建 shell 会话]\r\n";
            JSONObject result=connection.open(directory,shell.promptValue());process=result.getString("id");alive=true;accept(result);publish();
            waitForPrompt(0);
        }
    }
    synchronized Snapshot send(String text) throws Exception {
        ensureOpen();return input(text+"\r");
    }
    synchronized Snapshot connect() throws Exception {ensureOpen();return snapshot;}
    synchronized Snapshot input(String text) throws Exception {
        if(!alive){
            String printable=text.replaceAll("\u001b\\[[0-?]*[ -/]*[@-~]","").replaceAll("\u001bO.","");
            if(!printable.codePoints().anyMatch(value->!Character.isISOControl(value)&&!Character.isWhitespace(value)))return snapshot;
            ensureOpen();
        }
        connection.input(process,text);
        if(text.indexOf('\r')>=0||text.indexOf('\n')>=0||text.indexOf('\u0003')>=0||text.indexOf('\u0004')>=0||text.indexOf('\u001a')>=0)busy=true;
        publish();return snapshot;
    }
    synchronized void resize(int columns,int rows) throws Exception {if(alive&&!process.isEmpty())connection.resize(shell.token,columns,rows);}
    synchronized void saveScreen(String value,long rendered){
        saveScreen(value,rendered,0,0);
    }
    synchronized void saveScreen(String value,long rendered,int columns,int rows){
        if(value.length()>1500000||rendered<start||rendered>start+output.length())return;
        screen=value;screenOffset=rendered;screenColumns=columns;screenRows=rows;publish();
    }
    private void prepareIfIdle() throws Exception {
        if(!alive||busy||(interactive&&shellRevision>=2))return;
        // Upgrade an existing shell only once it returns to its own prompt.
        // A running editor/CLI receives no shell setup commands.
        interactive=true;shellRevision=2;busy=true;int before=shell.prompts;
        connection.input(process,TerminalShell.submit("unset __openpocket_token; . /usr/local/lib/openpocket/bash-session.sh"));
        waitForPrompt(before);
    }
    synchronized Snapshot clear() throws Exception {
        ensureOpen();if(busy)throw new Exception("当前程序仍在运行，请完成输入后再清空输出。");
        int before=shell.prompts;connection.input(process,TerminalShell.submit("clear"));busy=true;publish();waitForPrompt(before);
        if(shell.exitCode!=0)throw new Exception("清屏命令执行失败，输出已保留。");
        start+=output.length();output="";screen="";screenOffset=start;publish();return snapshot;
    }
    synchronized String[] complete(TerminalCompletion.Request request) throws Exception {
        ensureOpen();if(busy)throw new Exception("当前程序仍在运行，请完成后再补全。");
        int before=shell.prompts;shell.candidates=null;connection.input(process,shell.complete(request));busy=true;publish();waitForPrompt(before);
        if(shell.candidates==null)throw new Exception("没有收到补全结果，请重试。");
        LinkedHashSet<String> candidates=new LinkedHashSet<>();for(String line:shell.candidates.split("\n"))if(!line.isEmpty())candidates.add(line);
        shell.candidates=null;return candidates.toArray(new String[0]);
    }
    private void waitForPrompt(int before) throws Exception {
        long deadline=System.nanoTime()+8_000_000_000L;
        while(alive&&shell.prompts<=before&&System.nanoTime()<deadline){Thread.sleep(80);read();}
        if(!alive)throw new Exception("shell 会话已结束，请重新执行。");
        if(shell.prompts<=before)throw new Exception("等待 shell 响应超时；会话仍保留，请查看输出。");
    }
    private void accept(JSONObject response){
        int before=shell.prompts;JSONArray entries=response.optJSONArray("output");
        if(entries!=null)for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);if(entry!=null)output+=shell.append(entry.optString("data"));}
        offset=response.optInt("next_offset",offset);
        if(!shell.directory.isEmpty())directory=shell.directory;
        if(shell.prompts>before)busy=false;
        if("running".equals(response.optString("status")))alive=true;
        else ended("shell 已结束 · exit "+response.optString("exit_code","?"));
    }
    private void ended(String reason){if(alive)output+=NORMAL_SCREEN+"\r\n\u001b[2K["+reason+"]\r\n";alive=false;busy=false;}
    private void publish(){
        if(output.length()>500000){int trim=output.length()-500000;start+=trim;output=output.substring(trim);}
        // Empty polls must not JSON-encode and compare the entire terminal history.
        Snapshot previous=snapshot;
        if(previous!=null&&previous.output.equals(output)&&previous.screen.equals(screen)&&previous.directory.equals(directory)&&previous.process.equals(process)&&previous.busy==busy&&previous.alive==alive&&previous.start==start&&previous.screenOffset==screenOffset&&previous.screenColumns==screenColumns&&previous.screenRows==screenRows&&publishedOffset==offset&&publishedRevision==shellRevision&&publishedPending.equals(shell.pending))return;
        snapshot=new Snapshot(output,directory,process,busy,alive,start,screen,screenOffset,screenColumns,screenRows);
        publishedOffset=offset;publishedRevision=shellRevision;publishedPending=shell.pending;
        try{String saved=new JSONObject().put("format",2).put("interactive",interactive).put("shellRevision",shellRevision).put("token",shell.token).put("process",process).put("offset",offset).put("pending",shell.pending).put("directory",directory).put("output",output).put("start",start).put("screen",screen).put("screenOffset",screenOffset).put("screenColumns",screenColumns).put("screenRows",screenRows).put("busy",busy).put("alive",alive).toString();if(!saved.equals(lastSaved)){persist.accept(saved);lastSaved=saved;}}catch(Exception ignored){}
    }
    synchronized Snapshot uploaded(String path){output+="\r\n[已上传] "+path+"\r\n";publish();return snapshot;}
}
