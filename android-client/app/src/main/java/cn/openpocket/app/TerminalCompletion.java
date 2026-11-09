package cn.openpocket.app;

import java.util.ArrayList;
import java.util.List;

/** Parses the word at the cursor and safely asks bash for completion candidates. */
final class TerminalCompletion {
    static final class Request {
        final int start,end;
        final String prefix,mode;
        Request(int start,int end,String prefix,String mode){this.start=start;this.end=end;this.prefix=prefix;this.mode=mode;}
    }
    static Request parse(String draft,int position){
        int cursor=Math.max(0,Math.min(position,draft.length())),start=0;char quote=0;boolean escaped=false,active=false;StringBuilder word=new StringBuilder();List<String> words=new ArrayList<>();
        for(int i=0;i<cursor;i++){
            char c=draft.charAt(i);
            if(escaped){word.append(c);escaped=false;continue;}
            if(c=='\\'&&quote!='\''){if(!active){start=i;active=true;}escaped=true;continue;}
            if(quote!=0){if(c==quote)quote=0;else word.append(c);continue;}
            if(c=='\''||c=='"'){if(!active){start=i;active=true;}quote=c;continue;}
            if(c=='\n'||";|&()".indexOf(c)>=0){words.clear();word.setLength(0);active=false;start=i+1;continue;}
            if(Character.isWhitespace(c)){if(active){words.add(word.toString());word.setLength(0);}active=false;start=i+1;continue;}
            if(!active){start=i;active=true;}word.append(c);
        }
        int end=cursor;char suffixQuote=quote;boolean suffixEscape=escaped;
        while(end<draft.length()){
            char c=draft.charAt(end);
            if(suffixEscape){suffixEscape=false;end++;continue;}
            if(c=='\\'&&suffixQuote!='\''){suffixEscape=true;end++;continue;}
            if(suffixQuote!=0){if(c==suffixQuote)suffixQuote=0;end++;continue;}
            if(c=='\''||c=='"'){suffixQuote=c;end++;continue;}
            if(Character.isWhitespace(c)||";|&()".indexOf(c)>=0)break;
            end++;
        }
        boolean directory=!words.isEmpty()&&(words.get(0).equals("cd")||words.get(0).equals("pushd"));
        boolean command=words.isEmpty()||(words.size()==1&&(words.get(0).equals("sudo")||words.get(0).equals("command")||words.get(0).equals("exec")));
        return new Request(start,end,word.toString(),directory?"directory":command?"command":"file");
    }
    static String command(Request request){
        return "bash --noprofile --norc -c "+shellQuote("prefix=$1; mode=$2; "+script())+" openpocket-completion "+shellQuote(request.prefix)+" "+shellQuote(request.mode);
    }
    static String script(){
        return "case \"$prefix\" in '~/'*) prefix=\"$HOME/${prefix#??}\";; esac; "
            +"{ if [ \"$mode\" = command ]; then compgen -c -- \"$prefix\"; fi; if [ \"$mode\" = directory ]; then compgen -d -- \"$prefix\"; else compgen -f -- \"$prefix\"; fi; } "
            +"| LC_ALL=C sort -u | while IFS= read -r candidate; do if [ -d \"$candidate\" ]; then printf '%s/\\n' \"${candidate%/}\"; else printf '%s\\n' \"$candidate\"; fi; done | head -n 80";
    }
    private static String shellQuote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
    static String quote(String value){return !value.isEmpty()&&value.matches("[\\p{L}\\p{N}_./:@%+=,~\\-]+")?value:shellQuote(value);}
    static String commonPrefix(String[] values){String common=values[0];for(int i=1;i<values.length;i++){int count=0;while(count<common.length()&&count<values[i].length()&&common.charAt(count)==values[i].charAt(count))count++;common=common.substring(0,count);}return common;}
}
