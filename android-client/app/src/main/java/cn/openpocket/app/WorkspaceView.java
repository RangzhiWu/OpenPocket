package cn.openpocket.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashMap;

/** A terminal emulator with a docked keyboard and an editable command draft. */
final class WorkspaceView extends LinearLayout implements AutoCloseable {
    interface Host {
        void run(MainActivity.Job job,MainActivity.Result result);
        void run(MainActivity.Job job,MainActivity.Result result,Runnable failed);
        void chooseTerminal();
        void directoryChanged();
        void upload();
    }
    static final class State {
        String terminal="",directory="/",output="",command="";
        boolean running;
        final HashMap<String,TerminalSession> sessions=new HashMap<>();
    }
    private final Activity activity;
    private final Host host;
    private final State state;
    private final TerminalSession session;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final EditText command;
    private final TextView status;
    private final TerminalScreen screen;
    private final TerminalKeyboard keyboard;
    private final ArrayDeque<String> keys=new ArrayDeque<>();
    private boolean closed,polling,sending,resizing;
    private int columns,rows,sentColumns,sentRows;
    private String submitted="";
    private final Runnable pollAgain=this::poll;
    WorkspaceView(Activity activity,Api api,State state,String name,Host host){
        super(activity);this.activity=activity;this.host=host;this.state=state;
        TerminalApi terminal=new TerminalApi(api);
        String key=sessionKey(api,state.terminal);
        android.content.SharedPreferences saved=activity.getSharedPreferences("terminal-sessions",Context.MODE_PRIVATE);
        session=state.terminal.isEmpty()?null:state.sessions.computeIfAbsent(key,k->new TerminalSession(terminal,state.terminal,state.directory,saved.getString(k,""),value->saved.edit().putString(k,value).apply()));
        setOrientation(VERTICAL);setPadding(dp(12),dp(4),dp(12),dp(6));
        LinearLayout top=row();Button select=button(name.isEmpty()?"选择 OpenTerminal":name+" ▾");select.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        top.addView(select,new LayoutParams(0,dp(40),1));select.setOnClickListener(v->host.chooseTerminal());
        Button copy=button("复制");top.addView(copy,new LayoutParams(-2,dp(40)));
        Button upload=button("上传");top.addView(upload,new LayoutParams(-2,dp(40)));upload.setOnClickListener(v->{if(session==null)host.chooseTerminal();else host.upload();});
        Button clear=button("清屏");top.addView(clear,new LayoutParams(-2,dp(40)));addView(top);
        status=new TextView(activity);status.setTextSize(11);status.setTextColor(MainActivity.MUTED);status.setSingleLine(true);status.setPadding(dp(4),dp(5),0,dp(5));addView(status);
        screen=new TerminalScreen(activity,session,new TerminalScreen.Listener(){
            public void input(String data){sendKeys(data);}
            public void resize(int width,int height){columns=width;rows=height;WorkspaceView.this.resize();}
            public void ready(){if(session!=null)connect();}
        });
        screen.setBackground(UiStyle.surface(activity,MainActivity.PANEL,12,true));screen.setClipToOutline(true);
        addView(screen,new LayoutParams(-1,0,1));clear.setOnClickListener(v->screen.clearScreen());
        copy.setOnClickListener(v->new android.app.AlertDialog.Builder(activity).setTitle("复制终端内容").setItems(new String[]{"当前屏幕","含历史输出"},(dialog,which)->screen.copyText(which==0?"screen":"history")).setNegativeButton("取消",null).show());
        LinearLayout input=row();command=new TerminalCommandInput(activity,state.command);command.setHint("输入文本或命令");command.setMinLines(1);command.setMaxLines(4);command.setTypeface(Typeface.MONOSPACE);command.setTextSize(13);command.setPadding(dp(10),dp(6),dp(10),dp(6));input.addView(command,new LayoutParams(0,-2,1));
        command.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){state.command=s.toString();}public void afterTextChanged(Editable value){}});
        Button send=UiStyle.button(activity,"发送 ↵",true,true);LayoutParams sendLayout=new LayoutParams(-2,dp(40));sendLayout.leftMargin=dp(6);input.addView(send,sendLayout);send.setOnClickListener(v->submitCommand());
        keyboard=new TerminalKeyboard(activity,new TerminalKeyboard.Host(){
            public void text(String value,boolean paste){if(command.hasFocus()){replaceSelection(value);}else if(paste)screen.paste(value);else screen.key(value);}
            public void key(String value){if(session==null)host.chooseTerminal();else screen.key(value);}
            public void backspace(){if(command.hasFocus()){int a=Math.max(0,command.getSelectionStart()),b=Math.max(0,command.getSelectionEnd());if(a!=b)replaceSelection("");else if(a>0){int start=Character.offsetByCodePoints(command.getText(),a,-1);command.getText().delete(start,a);}}else screen.key("\u007f");}
            public void enter(){if(command.hasFocus())submitCommand();else screen.key("\r");}
        });
        Button showKeyboard=button("⌨ 键盘");showKeyboard.setContentDescription("展开或收起终端键盘");LayoutParams keyboardLayout=new LayoutParams(-2,dp(40));keyboardLayout.rightMargin=dp(5);input.addView(showKeyboard,0,keyboardLayout);showKeyboard.setOnClickListener(v->{if(keyboard.isShowing())keyboard.hide();else showKeyboard();});
        command.setOnClickListener(v->showKeyboard());
        android.view.GestureDetector taps=new android.view.GestureDetector(activity,new android.view.GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(android.view.MotionEvent event){return true;}
            @Override public boolean onSingleTapUp(android.view.MotionEvent event){focusCommand();showKeyboard();return false;}
        });
        screen.setOnTouchListener((view,event)->{taps.onTouchEvent(event);return false;});
        LayoutParams inputLayout=new LayoutParams(-1,-2);inputLayout.topMargin=dp(6);addView(input,inputLayout);
        LayoutParams dockLayout=new LayoutParams(-1,-2);dockLayout.topMargin=dp(6);addView(keyboard.view(),dockLayout);
        updateStatus();
        post(()->{if(!closed)hideSystemKeyboard();});
    }
    private void replaceSelection(String text){int a=Math.max(0,command.getSelectionStart()),b=Math.max(0,command.getSelectionEnd());command.getText().replace(Math.min(a,b),Math.max(a,b),text);}
    private void submitCommand(){if(session==null){host.chooseTerminal();return;}String text=command.getText().toString();if(text.isEmpty())screen.key("\r");else{submitted=text;screen.submit(text);}}
    private void hideSystemKeyboard(){((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindowToken(),0);}
    private void showKeyboard(){if(closed)return;hideSystemKeyboard();if(!command.hasFocus())focusCommand();keyboard.show();}
    boolean dismissKeyboard(){if(!keyboard.isShowing())return false;keyboard.hide();return true;}
    private static String sessionKey(Api api,String terminal){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest((api.base+"\n"+api.token+"\n"+api.sessionId()+"\n"+terminal).getBytes(StandardCharsets.UTF_8));StringBuilder key=new StringBuilder();for(byte value:digest)key.append(String.format("%02x",value&255));return key.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private void connect(){host.run(()->session.connect(),value->{if(!closed){accept();resize();poll();}},()->{if(!closed)status.setText("连接中断，重新进入工作区重试");});}
    private void sendKeys(String text){if(session==null||closed||text.isEmpty())return;keys.addLast(text);drainKeys();}
    private void drainKeys(){
        if(closed||sending||keys.isEmpty())return;sending=true;String text=keys.removeFirst();
        host.run(()->session.input(text),value->{sending=false;if(closed)return;if(!submitted.isEmpty()&&text.contains(submitted)){if(command.getText().toString().equals(submitted))command.setText("");submitted="";focusCommand();}accept();drainKeys();poll();},()->{sending=false;keys.clear();submitted="";if(!closed){accept();poll();}});
    }
    private void resize(){
        if(session==null||closed||resizing||columns<2||rows<2||!session.snapshot().alive||(sentColumns==columns&&sentRows==rows))return;
        resizing=true;int width=columns,height=rows;
        host.run(()->{session.resize(width,height);return null;},value->{resizing=false;sentColumns=width;sentRows=height;if(!closed){resize();poll();}},()->{resizing=false;});
    }
    private void accept(){
        TerminalSession.Snapshot value=session.snapshot();state.output=value.output;state.running=value.busy;
        if(!value.directory.equals(state.directory)){state.directory=value.directory;host.directoryChanged();}
        screen.render();updateStatus();
    }
    private void poll(){
        if(closed||session==null)return;handler.removeCallbacks(pollAgain);if(polling)return;polling=true;
        host.run(()->session.read(),value->{polling=false;if(closed)return;accept();if(session.snapshot().alive)handler.postDelayed(pollAgain,180);},()->{polling=false;if(!closed){status.setText("连接中断，正在重连…");handler.postDelayed(pollAgain,2000);}});
    }
    private void updateStatus(){status.setText(session==null?"选择终端后连接":session.snapshot().alive?"● 交互式终端 · "+state.directory:"○ shell 已结束 · 输入命令可新建会话");}
    private void focusCommand(){command.clearFocus();screen.requestFocus();screen.focusTerminal();}
    void uploaded(String directory,String name){if(session!=null)host.run(()->session.uploaded(TerminalApi.join(directory,name)),value->{if(!closed)accept();});}
    @Override public void close(){closed=true;handler.removeCallbacksAndMessages(null);keyboard.close();screen.close();}
    private int dp(int value){return UiStyle.dp(activity,value);}
    private LinearLayout row(){LinearLayout value=new LinearLayout(activity);value.setGravity(Gravity.CENTER_VERTICAL);return value;}
    private Button button(String text){return UiStyle.button(activity,text,false,true);}
}
