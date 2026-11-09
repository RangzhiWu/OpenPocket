package cn.openpocket.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class TerminalCommandTest {
    @Test public void directoryRecordCanBeSplitAtEveryStreamBoundary(){
        for(int boundary=0;boundary<100;boundary++){
            TerminalCommand command=new TerminalCommand();String record="before\n"+command.marker+"/home/中文 folder\u001fafter\n";int split=Math.min(boundary,record.length());
            String visible=command.append(record.substring(0,split))+command.append(record.substring(split))+command.finish();
            assertEquals("before\nafter\n",visible);assertEquals("/home/中文 folder",command.directory());
        }
    }
    @Test public void ordinaryOutputAndUnrelatedControlSequencesArePreserved(){
        TerminalCommand command=new TerminalCommand();String output="hi\u001eopenpocket_cwd_other:/tmp\u001f\n";
        assertEquals(output,command.append(output)+command.finish());assertEquals("",command.directory());
    }
    @Test public void missingDirectoryRecordDoesNotInventADirectory(){
        TerminalCommand command=new TerminalCommand();assertEquals("not found\n",command.append("not found\n")+command.finish());assertEquals("",command.directory());
    }
}
