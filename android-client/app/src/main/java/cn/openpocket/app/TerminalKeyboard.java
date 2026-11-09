package cn.openpocket.app;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

/** Docked in-app keyboard; its height is reserved by the workspace layout. */
final class TerminalKeyboard implements AutoCloseable {
    interface Host {
        void text(String value,boolean paste);
        void key(String value);
        void backspace();
        void enter();
    }
    private static final PinyinDictionary DICTIONARY=new PinyinDictionary();
    private static volatile boolean loading,loaded;
    private final Activity activity;
    private final Host host;
    private final LinearLayout root,letters,candidates;
    private final TextView composing;
    private final Button language,shift,ctrl,alt,symbol;
    private final Button[][] keys=new Button[4][];
    private boolean chinese,shifted,controlled,alted,symbols,closed;
    private String spelling="";
    private PinyinDictionary.Result result=new PinyinDictionary.Result(new String[0],0);
    private int opacity=1;
    TerminalKeyboard(Activity activity,Host host){
        this.activity=activity;this.host=host;
        root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(7),dp(5),dp(7),dp(7));
        GradientDrawable glass=UiStyle.rounded(activity,0xb917242a,18);glass.setStroke(dp(1),0x608fe0c4);root.setBackground(glass);
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.setVisibility(View.GONE);root.setContentDescription("底部终端键盘");
        LinearLayout title=row();TextView handle=new TextView(activity);handle.setText("终端键盘");handle.setTextSize(12);handle.setTextColor(0xffa9d5c8);handle.setGravity(Gravity.CENTER_VERTICAL);handle.setPadding(dp(6),0,0,0);title.addView(handle,new LinearLayout.LayoutParams(0,dp(30),1));
        language=button("EN / 中");title.addView(language,new LinearLayout.LayoutParams(dp(64),dp(30)));language.setOnClickListener(v->{commitFirst();chinese=!chinese;language.setText(chinese?"中 / EN":"EN / 中");refresh();});
        Button transparency=button("◐");transparency.setContentDescription("切换键盘透明度");title.addView(transparency,new LinearLayout.LayoutParams(dp(30),dp(30)));transparency.setOnClickListener(v->{int[] levels={135,185,225};opacity=(opacity+1)%levels.length;glass.setColor((levels[opacity]<<24)|0x0017242a);});
        Button hide=button("收起");title.addView(hide,new LinearLayout.LayoutParams(dp(44),dp(30)));hide.setOnClickListener(v->hide());root.addView(title);
        composing=new TextView(activity);composing.setTextSize(13);composing.setTextColor(MainActivity.GREEN);composing.setPadding(dp(5),dp(3),dp(5),dp(3));root.addView(composing,new LinearLayout.LayoutParams(-1,dp(25)));
        HorizontalScrollView choices=new HorizontalScrollView(activity);choices.setHorizontalScrollBarEnabled(false);candidates=row();choices.addView(candidates);root.addView(choices,new LinearLayout.LayoutParams(-1,dp(32)));
        HorizontalScrollView shortcuts=new HorizontalScrollView(activity);shortcuts.setHorizontalScrollBarEnabled(false);LinearLayout controls=row();
        String[][] values={{"Ctrl+C","\u0003"},{"Ctrl+D","\u0004"},{"Ctrl+Z","\u001a"},{"Esc","\u001b"},{"Tab","\t"},{"↑","\u001b[A"},{"↓","\u001b[B"},{"←","\u001b[D"},{"→","\u001b[C"},{"Ctrl+L","\u000c"},{"Home","\u001b[H"},{"End","\u001b[F"},{"PgUp","\u001b[5~"},{"PgDn","\u001b[6~"}};
        for(String[] value:values){Button key=button(value[0]);controls.addView(key,new LinearLayout.LayoutParams(dp(value[0].length()>3?58:37),dp(32)));key.setOnClickListener(v->{spelling="";refresh();host.key(value[1]);});if(value[0].equals("Ctrl+Z"))key.setOnLongClickListener(v->{Toast.makeText(activity,"暂停前台程序；输入 fg 可恢复",Toast.LENGTH_SHORT).show();return true;});}
        shortcuts.addView(controls);root.addView(shortcuts,new LinearLayout.LayoutParams(-1,dp(34)));
        letters=new LinearLayout(activity);letters.setOrientation(LinearLayout.VERTICAL);root.addView(letters);
        for(int row=0;row<4;row++){
            LinearLayout line=row();int count=row==2?9:10;keys[row]=new Button[count];
            for(int column=0;column<count;column++){final int r=row,c=column;Button key=button("");key.setTextSize(16);keys[row][column]=key;LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(0,dp(37),1);layout.setMargins(dp(1),dp(1),dp(1),dp(1));line.addView(key,layout);key.setOnClickListener(v->press(r,c));}
            letters.addView(line);
        }
        shift=keys[3][0];Button erase=keys[3][9];erase.setContentDescription("退格");
        LinearLayout bottom=row();ctrl=button("Ctrl");alt=button("Alt");symbol=button("符号");Button space=button("空格"),enter=button("↵");
        for(Button key:new Button[]{ctrl,alt,symbol,space,enter}){LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(0,dp(37),key==space?3:1.35f);layout.setMargins(dp(1),dp(2),dp(1),0);bottom.addView(key,layout);}root.addView(bottom);
        ctrl.setOnClickListener(v->{controlled=!controlled;refresh();});alt.setOnClickListener(v->{alted=!alted;refresh();});symbol.setOnClickListener(v->{symbols=!symbols;refresh();});
        space.setOnClickListener(v->{if(!spelling.isEmpty())commitFirst();else emit(" ");});enter.setContentDescription("终端回车");enter.setOnClickListener(v->{if(!spelling.isEmpty())commitFirst();else host.enter();});
        refresh();loadDictionary();
    }
    private void loadDictionary(){
        synchronized(TerminalKeyboard.class){if(loaded||loading)return;loading=true;}
        new Thread(()->{boolean success=false;try{DICTIONARY.load(activity.getAssets().open("keyboard/pinyin.bin"));success=true;}catch(java.io.IOException ignored){}finally{loaded=success;loading=false;activity.runOnUiThread(()->{if(!closed)refresh();});}},"terminal-pinyin").start();
    }
    private String[][] layout(){
        if(symbols&&shifted)return new String[][]{{"1","2","3","4","5","6","7","8","9","0"},{"!","@","#","$","%","^","&","*","(",")"},{"{","}","<",">","?","/","\\","[","]"},{"⇧",";",":","'","\"","`","~",".",",","⌫"}};
        if(symbols)return new String[][]{{"1","2","3","4","5","6","7","8","9","0"},{"!","@","#","$","%","^","&","*","(",")"},{"/","\\","|","-","_","=","+","[","]"},{"⇧",";",":","'","\"","`","~",".",",","⌫"}};
        return new String[][]{{"1","2","3","4","5","6","7","8","9","0"},{"q","w","e","r","t","y","u","i","o","p"},{"a","s","d","f","g","h","j","k","l"},{"⇧","z","x","c","v","b","n","m","/","⌫"}};
    }
    private void press(int row,int column){
        if(row==3&&column==0){shifted=!shifted;refresh();return;}
        if(row==3&&column==9){if(!spelling.isEmpty()){spelling=spelling.substring(0,spelling.length()-1);refresh();}else host.backspace();return;}
        String value=layout()[row][column];if(shifted)value=value.toUpperCase(Locale.ROOT);
        if(controlled||alted){emit(value);return;}
        if(chinese&&!symbols&&!shifted&&value.matches("[a-z]")&&spelling.length()<48){spelling+=value;refresh();return;}
        if(!spelling.isEmpty()&&value.matches("[1-9]")){int index=Integer.parseInt(value)-1;if(index<result.words.length){choose(index);return;}}
        if(!spelling.isEmpty())commitFirst();emit(value);
    }
    private void emit(String value){
        if(controlled||alted)spelling="";
        if(controlled){char letter=Character.toUpperCase(value.charAt(0));if(letter>='@'&&letter<='_')value=String.valueOf((char)(letter&31));}
        if(alted)value="\u001b"+value;
        if(controlled||alted)host.key(value);else host.text(value,false);
        shifted=false;controlled=false;alted=false;refresh();
    }
    private void commitFirst(){if(spelling.isEmpty())return;if(!loaded){Toast.makeText(activity,"拼音词库正在加载，请稍候",Toast.LENGTH_SHORT).show();return;}if(result.words.length>0)choose(0);else{host.text(spelling,true);spelling="";refresh();}}
    private void choose(int index){host.text(result.words[index],true);spelling=spelling.substring(result.consumed);refresh();}
    private void refresh(){
        String[][] values=layout();for(int row=0;row<keys.length;row++)for(int column=0;column<keys[row].length;column++)keys[row][column].setText(shifted?values[row][column].toUpperCase(Locale.ROOT):values[row][column]);
        if(ctrl!=null){ctrl.setTextColor(controlled?MainActivity.GREEN:MainActivity.TEXT);alt.setTextColor(alted?MainActivity.GREEN:MainActivity.TEXT);shift.setTextColor(shifted?MainActivity.GREEN:MainActivity.TEXT);symbol.setTextColor(symbols?MainActivity.GREEN:MainActivity.TEXT);}
        composing.setText(spelling.isEmpty()?(chinese?(loaded?"拼音输入 · 空格选字 · 无历史学习":"拼音词库加载中…"):"英文直输 · 不经系统输入法"):spelling);
        result=DICTIONARY.lookup(spelling);candidates.removeAllViews();
        for(int index=0;index<Math.min(48,result.words.length);index++){final int choice=index;Button candidate=button((index<9?(index+1)+" ":"")+result.words[index]);candidates.addView(candidate,new LinearLayout.LayoutParams(-2,dp(30)));candidate.setOnClickListener(v->choose(choice));}
    }
    View view(){return root;}
    boolean isShowing(){return root.getVisibility()==View.VISIBLE;}
    void show(){if(!closed)root.setVisibility(View.VISIBLE);}
    void hide(){root.setVisibility(View.GONE);}
    private Button button(String title){Button key=UiStyle.button(activity,title,false,false);key.setTextSize(12);key.setPadding(dp(3),0,dp(3),0);key.setFocusable(false);key.setBackground(UiStyle.surface(activity,0x403d5659,7,false));return key;}
    private LinearLayout row(){LinearLayout line=new LinearLayout(activity);line.setGravity(Gravity.CENTER_VERTICAL);return line;}
    private int dp(int value){return UiStyle.dp(activity,value);}
    @Override public void close(){closed=true;hide();spelling="";}
}
