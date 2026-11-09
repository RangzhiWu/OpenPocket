package cn.openpocket.app;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Fixed offline conversion candidates. No learned words or input persistence. */
final class PinyinDictionary {
    static final class Result {
        final String[] words;final int consumed;
        Result(String[] words,int consumed){this.words=words;this.consumed=consumed;}
    }
    private volatile Map<String,String[]> entries=Collections.emptyMap();
    void load(InputStream stream) throws java.io.IOException {
        Map<String,String[]> loaded=new HashMap<>();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(stream),StandardCharsets.UTF_8))){
            String line;while((line=reader.readLine())!=null){int tab=line.indexOf('\t');if(tab>0)loaded.put(line.substring(0,tab),line.substring(tab+1).split(" "));}
        }
        entries=loaded;
    }
    Result lookup(String spelling){
        String normalized=spelling.toLowerCase(java.util.Locale.ROOT).replace("'","");
        for(int length=normalized.length();length>0;length--){
            String[] words=entries.get(normalized.substring(0,length));
            if(words!=null){int consumed=0,count=0;while(consumed<spelling.length()&&count<length){if(spelling.charAt(consumed)!='\'')count++;consumed++;}while(consumed<spelling.length()&&spelling.charAt(consumed)=='\'')consumed++;return new Result(words,consumed);}
        }
        return new Result(new String[0],0);
    }
}
