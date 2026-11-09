package cn.openpocket.app;

import java.util.UUID;

/** Carries the shell's final working directory across separate execute requests. */
final class TerminalCommand {
    final String marker="\u001eopenpocket_cwd_"+UUID.randomUUID().toString().replace("-","")+":";
    private String pending="",directory="";
    String wrap(String command){
        String report="__openpocket_exit=$?; printf '\\036"+marker.substring(1)+"%s\\037' \"$PWD\"; exit \"$__openpocket_exit\"";
        return "trap "+TerminalApi.shellQuote(report)+" EXIT\n"+command;
    }
    String append(String chunk){
        pending+=chunk;StringBuilder visible=new StringBuilder();
        while(!pending.isEmpty()){
            int start=pending.indexOf(marker);
            if(start>=0){
                visible.append(pending,0,start);pending=pending.substring(start);
                int end=pending.indexOf('\u001f',marker.length());if(end<0)break;
                String path=pending.substring(marker.length(),end);
                if(path.startsWith("/")&&path.indexOf('\u0000')<0)directory=path;
                pending=pending.substring(end+1);
            }else{
                int keep=Math.min(pending.length(),marker.length()-1);
                while(keep>0&&!pending.endsWith(marker.substring(0,keep)))keep--;
                visible.append(pending,0,pending.length()-keep);pending=pending.substring(pending.length()-keep);break;
            }
        }
        return visible.toString();
    }
    String finish(){String remaining=pending;pending="";return remaining;}
    String directory(){return directory;}
}
