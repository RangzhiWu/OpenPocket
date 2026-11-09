package cn.openpocket.app;

import java.util.UUID;

/** Protocol carried by one long-lived interactive bash, never a per-command shell. */
final class TerminalShell {
    final String token;
    final String prompt,completion;
    String pending="",directory="",candidates=null;
    int exitCode,prompts;
    TerminalShell(){this(UUID.randomUUID().toString().replace("-",""));}
    TerminalShell(String token){
        if(!token.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid shell protocol token");
        this.token=token;prompt="\u001eopenpocket_shell_"+token+":";completion="\u001eopenpocket_complete_"+token+":";
    }
    String promptValue(){return prompt+"${?}:${PWD}\u001f";}
    static String startCommand(){
        // Claim the PTY as the controlling terminal so bash has working job control.
        String python="import fcntl,os,termios; fcntl.ioctl(0,termios.TIOCSCTTY,0); os.environ['SHELL']='/bin/bash'; os.execv('/bin/bash',['bash','--login','-i'])";
        return "exec /usr/bin/python3 -c "+quote(python);
    }
    static String submit(String command){return "eval -- "+quote(command)+"\n";}
    static String quote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
    String complete(TerminalCompletion.Request request){
        String function="__openpocket_complete_"+token;
        return "builtin printf '\\036"+completion.substring(1)+"'; "+function+"(){ local prefix="+quote(request.prefix)+" mode="+quote(request.mode)+"; "+TerminalCompletion.script()+"; }; "+function+"; unset -f "+function+"; builtin printf '\\037'\n";
    }
    String append(String chunk){
        pending+=chunk;StringBuilder visible=new StringBuilder();
        while(!pending.isEmpty()){
            int a=pending.indexOf(prompt),b=pending.indexOf(completion),start=a<0?b:b<0?a:Math.min(a,b);
            if(start>=0){
                visible.append(pending,0,start);pending=pending.substring(start);
                boolean ready=pending.startsWith(prompt);String marker=ready?prompt:completion;
                int end=pending.indexOf('\u001f',marker.length());if(end<0)break;
                String record=pending.substring(marker.length(),end);pending=pending.substring(end+1);
                if(ready){int colon=record.indexOf(':');try{if(colon>0){exitCode=Integer.parseInt(record.substring(0,colon));String path=record.substring(colon+1);if(path.startsWith("/"))directory=path;prompts++;}}catch(NumberFormatException ignored){}}
                else candidates=record.replace("\r","");
            }else{
                int keep=Math.min(pending.length(),Math.max(prompt.length(),completion.length())-1);
                while(keep>0&&!prompt.startsWith(pending.substring(pending.length()-keep))&&!completion.startsWith(pending.substring(pending.length()-keep)))keep--;
                visible.append(pending,0,pending.length()-keep);pending=pending.substring(pending.length()-keep);break;
            }
        }
        return visible.toString();
    }
}
