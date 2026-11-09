package cn.openpocket.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class TerminalCompletionTest {
    @Test public void commandsAndSudoCommandsUseCommandCompletion(){assertEquals("command",TerminalCompletion.parse("sys",3).mode);assertEquals("command",TerminalCompletion.parse("sudo sys",8).mode);assertEquals("sys",TerminalCompletion.parse("sudo sys",8).prefix);}
    @Test public void cdCompletesDirectoriesAndOrdinaryArgumentsCompleteFiles(){assertEquals("directory",TerminalCompletion.parse("cd /ho",6).mode);assertEquals("file",TerminalCompletion.parse("cat /ho",7).mode);}
    @Test public void quotesAndEscapesPreservePathsWithSpaces(){assertEquals("my folder/",TerminalCompletion.parse("cd 'my folder/",14).prefix);assertEquals("my folder/",TerminalCompletion.parse("cd my\\ folder/",14).prefix);assertEquals("'my folder/'",TerminalCompletion.quote("my folder/"));}
    @Test public void cursorInMiddleOfWordReplacesItsEntireOldSuffix(){TerminalCompletion.Request request=TerminalCompletion.parse("cat /home/file.txt --flag",9);assertEquals(4,request.start);assertEquals(18,request.end);assertEquals("/home",request.prefix);}
    @Test public void newShellSegmentsStartCommandCompletion(){assertEquals("command",TerminalCompletion.parse("ls | gr",7).mode);assertEquals("directory",TerminalCompletion.parse("ls; cd /ho",10).mode);}
    @Test public void shellMetacharactersAreQuotedAsLiteralPrefix(){String prefix="$(touch /tmp/never);'";TerminalCompletion.Request request=new TerminalCompletion.Request(0,prefix.length(),prefix,"file");String command=TerminalCompletion.command(request);assertTrue(command.endsWith("'$(touch /tmp/never);'\"'\"'' 'file'"));assertEquals("syst",TerminalCompletion.commonPrefix(new String[]{"systemctl","systemd","systool"}));}
}
