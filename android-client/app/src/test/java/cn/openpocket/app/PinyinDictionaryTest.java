package cn.openpocket.app;

import org.junit.Test;
import java.io.FileInputStream;
import static org.junit.Assert.*;

public class PinyinDictionaryTest {
    private PinyinDictionary dictionary() throws Exception {PinyinDictionary value=new PinyinDictionary();value.load(new FileInputStream("src/main/assets/keyboard/pinyin.bin"));return value;}
    @Test public void offlineChinesePhrasesAndSyllableBoundaries() throws Exception {
        PinyinDictionary value=dictionary();
        assertEquals("你好",value.lookup("nihao").words[0]);assertEquals(5,value.lookup("nihao").consumed);
        assertEquals("你好",value.lookup("ni'hao").words[0]);assertEquals(6,value.lookup("ni'hao").consumed);
        assertEquals("中文输入",value.lookup("zhongwenshuru").words[0]);
        assertEquals("你",value.lookup("ni").words[0]);assertTrue(value.lookup("lv").words.length>0);
    }
    @Test public void unknownInputHasNoPredictionAndChoicesDoNotLearnHistory() throws Exception {
        PinyinDictionary value=dictionary();assertEquals(0,value.lookup("").words.length);assertEquals(0,value.lookup("qqqq").words.length);
        String[] before=value.lookup("ni").words.clone();value.lookup("woaini");value.lookup("ceshi");assertArrayEquals(before,value.lookup("ni").words);
        PinyinDictionary.Result prefix=value.lookup("nihaoqxxx");assertEquals("你好",prefix.words[0]);assertEquals(5,prefix.consumed);
    }
}
