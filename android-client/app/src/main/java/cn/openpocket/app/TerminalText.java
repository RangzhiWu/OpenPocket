package cn.openpocket.app;

/** Render a terminal transcript as plain text without changing its stored bytes. */
final class TerminalText {
    private static final String SPINNER="⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";
    static String plain(String input) {
        if(input==null||input.isEmpty())return "";
        StringBuilder result=new StringBuilder(),line=new StringBuilder();
        int column=0;boolean moving=false;
        for(int i=0;i<input.length();){
            char c=input.charAt(i++);
            if(c=='\u001b'||c=='\u241b'||c=='\u009b'){
                if(i>=input.length())break;
                char kind=c=='\u009b'?'[':input.charAt(i++);
                if(kind=='['){
                    int start=i;while(i<input.length()&&(input.charAt(i)<'@'||input.charAt(i)>'~'))i++;
                    if(i>=input.length())break;
                    char operation=input.charAt(i++);String parameters=input.substring(start,i-1);int number=0;
                    try{number=Integer.parseInt(parameters.split(";",-1)[0]);}catch(NumberFormatException ignored){}
                    number=Math.min(10000,Math.max(0,number));
                    if(operation=='G'||operation=='`'){column=Math.max(0,number-1);moving=true;}
                    else if(operation=='C'){column=Math.min(10000,column+Math.max(1,number));moving=true;}
                    else if(operation=='D'){column=Math.max(0,column-Math.max(1,number));moving=true;}
                    else if(operation=='K'){
                        moving=true;
                        if(number==2)line.setLength(0);
                        else if(number==1){for(int p=0;p<Math.min(column+1,line.length());p++)line.setCharAt(p,' ');}
                        else if(column<line.length())line.setLength(column);
                    }
                }else if(kind==']'||kind=='P'||kind=='^'||kind=='_'){
                    while(i<input.length()){
                        char part=input.charAt(i++);
                        if(kind==']'&&part=='\u0007')break;
                        if((part=='\u001b'||part=='\u241b')&&i<input.length()&&input.charAt(i)=='\\'){i++;break;}
                    }
                }
                continue;
            }
            if(c=='\r'){column=0;moving=true;continue;}
            if(c=='\b'){column=Math.max(0,column-1);moving=true;continue;}
            if(c=='\n'){
                if(!moving||!spinner(line))result.append(line).append('\n');
                line.setLength(0);column=0;moving=false;continue;
            }
            if(c<' '&&c!='\t'||c=='\u007f')continue;
            if(moving&&spinner(line)&&!Character.isWhitespace(c)&&SPINNER.indexOf(c)<0){line.setLength(0);column=0;}
            while(line.length()<column)line.append(' ');
            if(column<line.length())line.setCharAt(column,c);else line.append(c);
            column++;
        }
        if(!moving||!spinner(line))result.append(line);
        return result.toString();
    }
    private static boolean spinner(StringBuilder line){
        boolean found=false;
        for(int i=0;i<line.length();i++){char c=line.charAt(i);if(SPINNER.indexOf(c)>=0)found=true;else if(!Character.isWhitespace(c))return false;}
        return found;
    }
}
