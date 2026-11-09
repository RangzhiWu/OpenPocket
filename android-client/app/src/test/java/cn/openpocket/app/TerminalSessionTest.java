package cn.openpocket.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class TerminalSessionTest {
    private static final class Server implements TerminalSession.Connection {
        int opens;String marker,lastInput="";boolean alive=true,offline=false,reading=false;
        final List<String> output=new ArrayList<>();
        public JSONObject open(String cwd,String prompt) throws Exception {
            opens++;alive=true;marker=prompt.substring(0,prompt.indexOf("${?}"));output.clear();output.add(marker+"0:"+cwd+"\u001f");return response(0);
        }
        public JSONObject read(String process,int offset) throws Exception {
            if(offline)throw new java.io.IOException("offline");return response(offset);
        }
        public void input(String process,String text){
            lastInput=text;
            if(reading){reading=false;output.add("received:"+text+marker+"0:/home\u001f");}
            else if(text.contains("read -r reply")){reading=true;}
            else output.add("ok\n"+marker+"0:/home\u001f");
        }
        JSONObject response(int offset) throws Exception {
            JSONArray entries=new JSONArray();for(int i=offset;i<output.size();i++)entries.put(new JSONObject().put("data",output.get(i)));
            return new JSONObject().put("id","same-process").put("status",alive?"running":"done").put("exit_code",0).put("output",entries).put("next_offset",output.size());
        }
    }
    @Test public void commandsAndApplicationRestartReuseExactlyOneProcess() throws Exception {
        Server server=new Server();AtomicReference<String> saved=new AtomicReference<>("");
        TerminalSession session=new TerminalSession(server,"/home","",saved::set);
        session.send("VALUE=retained");session.read();session.send("echo $VALUE");session.read();assertEquals(1,server.opens);
        TerminalSession restored=new TerminalSession(server,"/",saved.get(),saved::set);
        restored.read();restored.send("pwd");restored.read();assertEquals(1,server.opens);
        assertEquals("same-process",restored.snapshot().process);assertEquals("/home",restored.snapshot().directory);assertFalse(restored.snapshot().busy);
        assertEquals(3,restored.snapshot().output.split("ok",-1).length-1);
    }
    @Test public void connectionFailureDoesNotCreateAnotherShellOrResendCommand() throws Exception {
        Server server=new Server();TerminalSession session=new TerminalSession(server,"/home","",v->{});
        session.send("echo once");session.read();server.offline=true;
        try{session.send("echo twice");fail("Expected offline error");}catch(java.io.IOException expected){}
        assertEquals(1,server.opens);assertFalse(server.lastInput.contains("twice"));
        server.offline=false;session.read();session.send("echo twice");assertEquals(1,server.opens);
    }
    @Test public void foregroundInputUsesExistingProcessAndClearKeepsItAlive() throws Exception {
        Server server=new Server();TerminalSession session=new TerminalSession(server,"/home","",v->{});
        session.send("read -r reply");session.read();assertTrue(session.snapshot().busy);
        session.send("literal \\n 中文");assertEquals("literal \\n 中文\r",server.lastInput);session.read();assertFalse(session.snapshot().busy);
        session.clear();assertEquals("",session.snapshot().output);assertTrue(session.snapshot().alive);assertEquals(1,server.opens);
    }
    @Test public void onlyAnExitedShellIsReplaced() throws Exception {
        Server server=new Server();TerminalSession session=new TerminalSession(server,"/home","",v->{});
        session.send("exit");server.alive=false;session.read();assertFalse(session.snapshot().alive);
        session.send("pwd");assertEquals(2,server.opens);assertTrue(session.snapshot().output.contains("已新建 shell 会话"));
    }
    @Test public void resizingAndExitKeysNeverRecreateAnExitedShell() throws Exception {
        Server server=new Server();TerminalSession session=new TerminalSession(server,"/home","",v->{});
        session.connect();server.alive=false;session.read();String ended=session.snapshot().output;
        for(String key:new String[]{"\u0003","\u0004","\u001a","\r","\t","\u001b[A","\u001b[1;1R"})session.input(key);
        session.resize(50,28);session.read();assertEquals(1,server.opens);assertEquals(ended,session.snapshot().output);
        session.input("printf ok\r");assertEquals(2,server.opens);assertTrue(session.snapshot().alive);
    }
    @Test public void exitedShellRestoresNormalTerminalModesBeforeItsNotice() throws Exception {
        Server server=new Server();TerminalSession session=new TerminalSession(server,"/home","",v->{});
        session.connect();server.output.add("\u001b[?1049h\u001b[5;10r\u001b[?25lMENU");session.read();
        server.alive=false;session.read();String output=session.snapshot().output;
        assertTrue(output.indexOf("\u001b[?1049l")>output.indexOf("MENU"));
        assertTrue(output.contains("\u001b[!p"));assertTrue(output.contains("\u001b[2K[shell 已结束"));
    }
    @Test public void terminalInputPreservesEnterEscapeControlKeysAndUnicode() throws Exception {
        Server server=new Server();TerminalSession session=new TerminalSession(server,"/home","",v->{});
        session.connect();
        for(String input:new String[]{"/model\r","\u0003","\u0004","\u001a","\u001b[A","\u001b","\t","\u001b[1;2R","中文\\n"}){
            session.input(input);assertEquals(input,server.lastInput);
        }
        assertEquals(1,server.opens);
    }
    @Test public void ansiAndAlternateScreenSurviveReadsAndRestore() throws Exception {
        Server server=new Server();AtomicReference<String> saved=new AtomicReference<>("");
        TerminalSession session=new TerminalSession(server,"/home","",saved::set);session.connect();
        String ansi="\u001b[?1049h\u001b[2J\u001b[1;1H菜单\u001b[31m红\u001b[0m\r\n";
        server.output.add(ansi);session.read();assertTrue(session.snapshot().output.endsWith(ansi));
        long offset=session.snapshot().end;session.saveScreen("serialized screen",offset,54,28);
        TerminalSession restored=new TerminalSession(server,"/",saved.get(),saved::set);
        assertEquals("serialized screen",restored.snapshot().screen);assertEquals(offset,restored.snapshot().screenOffset);
        assertEquals(54,restored.snapshot().screenColumns);assertEquals(28,restored.snapshot().screenRows);
        restored.input("\u0003");assertEquals(1,server.opens);
    }
}
