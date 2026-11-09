package cn.openpocket.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

/** Locally bundled terminal emulator. Credentials and networking remain native. */
@SuppressLint("SetJavaScriptEnabled")
final class TerminalScreen extends WebView {
    interface Listener {
        void input(String data);
        void resize(int columns,int rows);
        void ready();
    }
    private final TerminalSession session;
    private final Listener listener;
    private boolean ready,closed;
    private long rendered=-1;
    TerminalScreen(Context context,TerminalSession session,Listener listener){
        super(context);this.session=session;this.listener=listener;
        setBackgroundColor(MainActivity.PANEL);
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        getSettings().setJavaScriptEnabled(true);
        getSettings().setAllowFileAccess(false);
        getSettings().setAllowContentAccess(false);
        getSettings().setBlockNetworkLoads(true);
        setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
        });
        addJavascriptInterface(new Bridge(),"PocketTerminal");
        setOnLongClickListener(view->{copyText("screen");return true;});
        loadUrl("file:///android_asset/terminal/index.html");
    }
    @Override public boolean onCheckIsTextEditor(){return false;}
    @Override public InputConnection onCreateInputConnection(EditorInfo attributes){return null;}
    private final class Bridge {
        @JavascriptInterface public void ready(){post(()->{if(!closed){ready=true;android.view.accessibility.AccessibilityManager accessibility=(android.view.accessibility.AccessibilityManager)getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);evaluateJavascript("window.pocket.accessibility("+(accessibility!=null&&accessibility.isTouchExplorationEnabled())+")",null);render();listener.ready();}});}
        @JavascriptInterface public void input(String data){if(data.length()<=65536)post(()->{if(!closed)listener.input(data);});}
        @JavascriptInterface public void resize(int columns,int rows){post(()->{if(!closed&&columns>=2&&columns<=500&&rows>=2&&rows<=300)listener.resize(columns,rows);});}
        @JavascriptInterface public void snapshot(String state,String offset,int columns,int rows){
            if(closed||session==null)return;
            if(columns<2||columns>500||rows<2||rows>300)return;
            try{session.saveScreen(state,Long.parseLong(offset),columns,rows);}catch(NumberFormatException ignored){}
        }
        @JavascriptInterface public void copy(String text){post(()->{if(!closed)showCopy(text);});}
    }
    void render(){
        if(!ready||closed||session==null)return;
        TerminalSession.Snapshot value=session.snapshot();
        boolean reset=rendered<value.start||rendered>value.end;
        if(!reset&&rendered==value.end)return;
        long from=reset?value.start:rendered;
        String initial="";
        if(reset&&!value.screen.isEmpty()&&value.screenColumns>=2&&value.screenRows>=2&&value.screenOffset>=value.start&&value.screenOffset<=value.end){initial=value.screen;from=value.screenOffset;}
        try{
            JSONObject update=new JSONObject().put("reset",reset).put("initial",initial).put("data",value.output.substring((int)(from-value.start))).put("end",Long.toString(value.end)).put("cols",value.screenColumns).put("rows",value.screenRows);
            evaluateJavascript("window.pocket.render("+update+")",null);rendered=value.end;
        }catch(Exception ignored){}
    }
    void key(String data){if(ready&&!closed)evaluateJavascript("window.pocket.key("+JSONObject.quote(data)+")",null);}
    void submit(String text){if(ready&&!closed)evaluateJavascript("window.pocket.submit("+JSONObject.quote(text)+")",null);}
    void paste(String text){if(ready&&!closed)evaluateJavascript("window.pocket.paste("+JSONObject.quote(text)+")",null);}
    void clearScreen(){if(ready&&!closed)evaluateJavascript("window.pocket.clear()",null);}
    void copyText(String mode){if(ready&&!closed)evaluateJavascript("window.pocket.copy("+JSONObject.quote(mode)+")",null);}
    private void showCopy(String value){
        if(value.isEmpty()){Toast.makeText(getContext(),"当前没有可复制的内容",Toast.LENGTH_SHORT).show();return;}
        TextView text=new TextView(getContext());text.setText(value);text.setTextSize(13);text.setTextIsSelectable(true);text.setTypeface(android.graphics.Typeface.MONOSPACE);int padding=UiStyle.dp(getContext(),16);text.setPadding(padding,padding,padding,padding);
        ScrollView scroll=new ScrollView(getContext());scroll.addView(text);
        new AlertDialog.Builder(getContext()).setTitle("选择或复制终端文本").setView(scroll).setPositiveButton("复制全部",(dialog,which)->{
            ClipboardManager clipboard=(ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("终端",value));Toast.makeText(getContext(),"已复制",Toast.LENGTH_SHORT).show();
        }).setNegativeButton("关闭",null).show();
    }
    void focusTerminal(){if(ready&&!closed)evaluateJavascript("window.pocket.focus()",null);}
    void close(){
        if(closed)return;closed=true;removeJavascriptInterface("PocketTerminal");stopLoading();destroy();
    }
}
