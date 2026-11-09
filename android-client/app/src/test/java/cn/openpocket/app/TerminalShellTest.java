package cn.openpocket.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class TerminalShellTest {
    private static final String TOKEN="0123456789abcdef0123456789abcdef";
    @Test public void promptAndCompletionRecordsSurviveEveryStreamBoundary(){
        TerminalShell template=new TerminalShell(TOKEN);
        String data="before"+template.prompt+"2:/home/中文 folder\u001fmiddle"+template.completion+"keep_alias\r\nkeep_func\r\n\u001fafter";
        for(int split=0;split<=data.length();split++){
            TerminalShell shell=new TerminalShell(TOKEN);
            assertEquals("beforemiddleafter",shell.append(data.substring(0,split))+shell.append(data.substring(split)));
            assertEquals(2,shell.exitCode);assertEquals(1,shell.prompts);assertEquals("/home/中文 folder",shell.directory);assertEquals("keep_alias\nkeep_func\n",shell.candidates);assertEquals("",shell.pending);
        }
    }
    @Test public void parserCanResumePartialRecordAfterApplicationRestart(){
        TerminalShell first=new TerminalShell(TOKEN);assertEquals("visible",first.append("visible"+first.prompt+"0:/ho"));
        TerminalShell restored=new TerminalShell(TOKEN);restored.pending=first.pending;
        assertEquals("",restored.append("me\u001f"));assertEquals("/home",restored.directory);assertEquals(1,restored.prompts);
    }
    @Test public void commandsRunInExistingShellWithoutExitTrapOrChildShell(){
        String text="VALUE='literal \\n 中文'\nprintf '%s' \"$VALUE\"";
        String submitted=TerminalShell.submit(text);
        assertEquals("eval -- "+TerminalShell.quote(text)+"\n",submitted);
        assertFalse(submitted.contains("bash -c"));assertFalse(submitted.contains("trap "));
        assertTrue(TerminalShell.startCommand().contains("TIOCSCTTY"));
    }
}
