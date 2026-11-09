package cn.openpocket.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class TerminalTextTest {
    @Test public void npmAnimationKeepsWarningsAndResults(){
        StringBuilder log=new StringBuilder("---\n");
        for(int n=0;n<400;n++)log.append("\u001b[1G\u001b[0K⠙\u001b[1G\u001b[0K⠹");
        log.append("\u001b[1mnpm\u001b[22m \u001b[33mwarn\u001b[39m deprecated package\r\n");
        log.append("\u001b[1G\u001b[0K⠋\u001b[1G\u001b[0Kadded 42 packages\r\n");
        assertEquals("---\nnpm warn deprecated package\nadded 42 packages\n",TerminalText.plain(log.toString()));
    }
    @Test public void carriageReturnAndEraseRenderLatestLine(){
        assertEquals("progress 100%\nDone\n",TerminalText.plain("progress 1%\r\u001b[Kprogress 100%\r\nDone\r\n"));
        assertEquals("xbc\n",TerminalText.plain("abc\rx\n"));
    }
    @Test public void hyperlinksKeepLabelsAndDiscardControlPayload(){
        assertEquals("Docs\n",TerminalText.plain("\u001b]8;;https://example.invalid\u001b\\Docs\u001b]8;;\u0007\n"));
        assertEquals("ok",TerminalText.plain("\u001b]0;window title\u0007ok\u001b["));
    }
    @Test public void visibleEscapePicturesFromOldTranscriptsAreHandled(){
        assertEquals("npm warn\n",TerminalText.plain("␛[1G␛[0K⠙␛[1mnpm␛[22m warn\n"));
        assertEquals("red",TerminalText.plain("\u009b31mred\u009b0m"));
    }
    @Test public void ordinaryTextUnicodeBrailleAndBackslashesArePreserved(){
        String text="中文 😀\n⠋⠙ are text\nC:\\tmp\\new\\file\t 123\n";
        assertEquals(text,TerminalText.plain(text));
        assertEquals("ERROR: failed\n",TerminalText.plain("\u001b[31mERROR: failed\u001b[0m\r\n"));
    }
    @Test public void cursorBackspaceAndIncompleteControlsDoNotLeak(){
        assertEquals("aXc",TerminalText.plain("abc\b\bX"));
        assertEquals("final",TerminalText.plain("old text\u001b[1G\u001b[2Kfinal"));
        assertEquals("",TerminalText.plain("\u001b[1G\u001b[0K⠹"));
        assertTrue(TerminalText.plain("\u001b[999999999999999999Gx").length()<10002);
    }
}
