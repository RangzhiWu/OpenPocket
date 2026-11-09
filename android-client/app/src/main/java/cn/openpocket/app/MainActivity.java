package cn.openpocket.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    static final int BG=0xff10151e, PANEL=0xff19222f, CARD=0xff202c3b, TEXT=0xffeef3f8,
        MUTED=0xffa0afc2, GREEN=0xff8fe0c4, ORANGE=0xfff5c884, RED=0xffffa5a5;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newFixedThreadPool(3);
    private final ExecutorService uploads=Executors.newSingleThreadExecutor();
    private final ExecutorService modelWrites=Executors.newSingleThreadExecutor();
    private final ExecutorService localReads=Executors.newSingleThreadExecutor();
    private final ExecutorService historySync=Executors.newSingleThreadExecutor();
    private final ExecutorService usageSync=Executors.newSingleThreadExecutor();
    private UsageCache usageCache;
    private boolean usageLoading;
    private String usageError="";
    private final Map<String,Long> historyRetryAt=new java.util.concurrent.ConcurrentHashMap<>();
    private boolean localHistoryLoading;
    private SecureStore store;
    private Api api;
    private HistoryRepository history;
    private LinearLayout root, body, header, navigation, messageList, chatRows;
    private LinearLayout attachmentRows;
    private Button terminalButton;
    private JSONArray terminals=new JSONArray();
    private JSONObject preferences=new JSONObject();
    private ChatModelSelection chatModels=new ChatModelSelection(preferences);
    private final Map<String,Long> modelSyncs=new HashMap<>(),modelRetryAt=new HashMap<>();
    private final WorkspaceView.State workspace=new WorkspaceView.State();
    private WorkspaceView workspaceView;
    private final Map<String,List<Attachment>> attachments=new HashMap<>();
    private final Map<String,JSONArray> workspaceAttachments=new HashMap<>();
    private String pickerChat="",pickerTerminal="",pickerDirectory="";
    private int pickerEpoch;
    private boolean pickerWorkspace;
    private ScrollView messageScroll;
    private TextView connection, chatStatus;
    private EditText composer;
    private Button send, modelButton;
    private String tab="chats", chatId="", fingerprint="", loginError="";
    private JSONArray chats=new JSONArray(), models=new JSONArray();
    private JSONObject current=new JSONObject(), usage=new JSONObject();
    private boolean polling=false, resumed=false, sending=false, connected=false, cacheReady=false, prefetching=false, currentVerified=false, authenticationExpired=false;
    private volatile int sessionEpoch=0;
    private int selectedPage=0;
    private long currentSavedAt=0, liveRevision=0;
    private boolean liveDirty=false, forceRefreshPending=false;
    private final Set<String> cachedChatIds=new HashSet<>();
    private final Map<String,String> drafts=new HashMap<>();
    private final Set<String> expanded=new HashSet<>();
    private LiveSession live;
    private final Runnable liveRefresh=()->refresh(false);
    interface Job { Object run() throws Exception; }
    interface Result { void accept(Object value) throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store=new SecureStore(this); api=new Api(store.server(),store.token());
        if(state!=null) { chatId=state.getString("chatId",""); tab=state.getString("tab","chats"); }
        else chatId=store.lastChat();
        authenticationExpired=store.expired();
        if(api.token.isEmpty()) login(); else startSession();
    }
    @Override public void onSaveInstanceState(Bundle state) {
        rememberDraft(); state.putString("chatId",chatId); state.putString("tab",tab);
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() { super.onResume(); resumed=true; ensureLive();ui.post(poll); }
    @Override protected void onPause() { resumed=false; ui.removeCallbacks(poll);ui.removeCallbacks(liveRefresh);rememberDraft();if(live!=null){live.close();live=null;}super.onPause(); }
    @Override protected void onDestroy() { if(workspaceView!=null)workspaceView.close();if(live!=null)live.close();ui.removeCallbacksAndMessages(null); io.shutdownNow();uploads.shutdownNow();modelWrites.shutdownNow();localReads.shutdownNow();historySync.shutdownNow();usageSync.shutdownNow();if(history!=null)history.close(false); super.onDestroy(); }
    private void ensureLive(){if(!api.token.isEmpty()&&!authenticationExpired&&live==null){live=new LiveSession(api,changed->ui.post(()->{if(resumed){if(changed){liveDirty=true;liveRevision++;}ui.removeCallbacks(liveRefresh);ui.postDelayed(liveRefresh,200);}}));live.connect();}}
    private final Runnable poll=new Runnable() { @Override public void run() {
        if(!resumed)return;
        if(!api.token.isEmpty()) refresh(false);
        ui.postDelayed(this,2500);
    }};

    private void run(Job job,Result result) { run(job,result,true); }
    private void run(Job job,Result result,boolean showError) {
        run(job,result,showError,null);
    }
    private void run(Job job,Result result,boolean showError,Runnable failed) {
        final int epoch=sessionEpoch;
        io.execute(()->{ try {
            Object value=job.run(); ui.post(()->{ if(isDestroyed()||epoch!=sessionEpoch)return; try { result.accept(value); } catch(Exception e) { error(e); } });
        } catch(Exception e) { ui.post(()->{ if(isDestroyed()||epoch!=sessionEpoch)return;
            if(failed!=null)failed.run();
            if(e instanceof Api.AuthException) expireSession();
            else { setConnection(false); if(showError)error(e); }
        }); }});
    }
    private void error(Exception e) {
        String message=e.getMessage();
        if(message==null || e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException)
            message="无法连接服务器，请检查服务器地址、网络或私人 VPN 连接。";
        Toast.makeText(this,message,Toast.LENGTH_LONG).show();
    }
    private void logout() {
        if(workspaceView!=null){workspaceView.close();workspaceView=null;}workspace.sessions.clear();getSharedPreferences("terminal-sessions",MODE_PRIVATE).edit().clear().apply();attachments.clear();workspaceAttachments.clear();terminals=new JSONArray();preferences=new JSONObject();chatModels=new ChatModelSelection(preferences);modelSyncs.clear();modelRetryAt.clear();workspace.terminal="";workspace.running=false;workspace.output="";
        rememberDraft();if(live!=null){live.close();live=null;}sessionEpoch++;if(history!=null){history.close(true);history=null;}store.clear();api=new Api(store.server(),"");chats=new JSONArray();models=new JSONArray();current=new JSONObject();usage=new JSONObject();chatId="";drafts.clear();expanded.clear();cachedChatIds.clear();polling=false;prefetching=false;cacheReady=false;authenticationExpired=false;connected=false;sending=false;composer=null;login();
    }
    private void expireSession() {
        if(authenticationExpired)return;
        authenticationExpired=true;store.expired(true);if(live!=null){live.close();live=null;}setConnection(false);renderMessages();
        Toast.makeText(this,"登录已过期，本地记录仍可查看，请到设置重新登录。",Toast.LENGTH_LONG).show();
    }
    private void login() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root=vertical();root.setPadding(dp(26),dp(45),dp(26),dp(20));root.setBackgroundColor(BG);setContentView(root);
        TextView logo=label("OP / OPENPOCKET",14,GREEN);logo.setTypeface(null,Typeface.BOLD);root.addView(logo);
        space(root,35);root.addView(label("你的服务器，\n随时在手边。",32,TEXT));space(root,12);
        root.addView(label("连接 Open WebUI，继续对话与任务。",14,MUTED));space(root,30);
        EditText server=field("服务器地址",store.server(),false);root.addView(server);space(root,10);
        EditText email=field("邮箱",store.email(),false);email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);root.addView(email);space(root,10);
        EditText password=field("密码","",true);root.addView(password);space(root,20);
        Button login=button("连接服务器",true);root.addView(login,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView status=label(loginError,13,RED);root.addView(status);space(root,15);
        root.addView(label("填写你自己的 Open WebUI 服务器地址。私人网络服务器需先连接相应 VPN。\n密码仅用于登录，不保存到手机；登录令牌使用 Android 密钥库加密保存。",12,MUTED));
        login.setOnClickListener(v->{
            try {
                String base=Api.validateServer(server.getText().toString());
                String address=email.getText().toString().trim(),secret=password.getText().toString();
                if(address.isEmpty()||secret.isEmpty())throw new Exception("请输入邮箱和密码");
                login.setEnabled(false);status.setText("正在验证…");
                run(()->{
                    Api candidate=new Api(base,"");JSONObject result=candidate.post("/api/v1/auths/signin",new JSONObject().put("email",address).put("password",secret));
                    String token=result.optString("token");if(token.isEmpty())throw new Exception("服务器没有返回登录令牌");
                    store.saveToken(token);store.server(base);store.email(address);return new Api(base,token);
                },value->{ api=(Api)value;password.setText("");loginError="";authenticationExpired=false;startSession(); },true,()->{login.setEnabled(true);status.setText("连接失败，请检查网络或登录信息。");});
            }catch(Exception e){status.setText(e.getMessage());}
        });
    }
    private void startSession() {
        cacheReady=false;currentVerified=false;currentSavedAt=0;liveDirty=false;forceRefreshPending=false;connected=false;usage=new JSONObject();usageLoading=false;usageError="";historyRetryAt.clear();
        final Api client=api;final int epoch=sessionEpoch;
        final HistoryRepository repository=new HistoryRepository(this,client.base,store.email(),id->client.get("/api/v1/chats/"+id));history=repository;
        final UsageCache stats=new UsageCache(new UsageCache.Storage(){public JSONObject read()throws Exception{return repository.usage();}public void write(JSONObject value)throws Exception{repository.usage(value);}},()->client.get("/api/v1/users/usage?days=30"),System::currentTimeMillis);usageCache=stats;
        shell();
        final String selected=chatId;
        localReads.execute(()->{try{
            JSONArray list=repository.list(),savedModels=repository.models();JSONObject savedPreferences=repository.preferences();Set<String> ids=repository.cachedIds();
            stats.load();
            HistoryRepository.Snapshot snapshot=selected.isEmpty()?null:repository.cached(selected);
            ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;
                chats=list;models=savedModels;preferences=savedPreferences;chatModels=new ChatModelSelection(preferences);modelSyncs.clear();modelRetryAt.clear();cachedChatIds.addAll(ids);restoreTerminal();
                if(selected.equals(chatId)&&snapshot!=null){current=snapshot.response;currentSavedAt=snapshot.savedAt;observeModel(selected,current,chatModels.revision(selected));}
                usage=stats.value();cacheReady=true;renderPage();updateConnectionLabel();prefetchHistory(list);refresh(false);loadModels();loadTerminals(false);
            });
        }catch(Exception e){ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;cacheReady=true;error(e);refresh(false);});}});
    }
    private void loadModels() {
        if(authenticationExpired)return;final Api client=api;final HistoryRepository repository=history;
        run(()->{JSONArray list=client.get("/api/models").optJSONArray("data");if(list==null)list=new JSONArray();repository.saveModels(list);return list;},v->{models=(JSONArray)v;updateModelButton();},false);
    }
    private void shell() {
        ensureLive();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root=vertical();root.setBackgroundColor(BG);setContentView(root);
        header=horizontal();header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(18),dp(12),dp(14),dp(10));
        LinearLayout branding=vertical();TextView title=label("OpenPocket",22,TEXT);title.setTypeface(null,Typeface.BOLD);branding.addView(title);
        connection=label("正在读取本地记录…",11,MUTED);branding.addView(connection);header.addView(branding,new LinearLayout.LayoutParams(0,-2,1));
        Button refresh=button("↻",false);refresh.setContentDescription("刷新数据");header.addView(refresh,new LinearLayout.LayoutParams(dp(48),dp(44)));refresh.setOnClickListener(v->refresh(true));root.addView(header);
        body=vertical();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        navigation=horizontal();navigation.setPadding(dp(8),dp(4),dp(8),dp(5));root.addView(navigation);
        renderNavigation();renderPage();
    }
    private void nav(String title,String id) {
        Button b=button(title,false);b.setTextColor(tab.equals(id)?GREEN:MUTED);navigation.addView(b,new LinearLayout.LayoutParams(0,dp(47),1));
        b.setOnClickListener(v->{rememberDraft();tab=id;composer=null;renderNavigation();renderPage();refresh(false);});
    }
    private void renderNavigation(){navigation.removeAllViews();nav("对话","chats");nav("工作区","workspace");nav("任务","tasks");nav("用量","usage");nav("设置","settings");}
    private void setConnection(boolean value) {
        connected=value&&!authenticationExpired;updateConnectionLabel();
    }
    private void updateConnectionLabel() {
        if(connection==null)return;
        int saved=0;for(int i=0;i<chats.length();i++){JSONObject item=chats.optJSONObject(i);if(item!=null&&cachedChatIds.contains(item.optString("id")))saved++;}
        String progress=" · 手机已保存 "+saved+"/"+chats.length()+" 个对话";
        String text=(authenticationExpired?"○ 登录已过期":connected?"● 已连接":"○ 连接中断，正在重试")+progress;
        connection.setText(text);connection.setTextColor(connected?GREEN:ORANGE);
    }
    private void refresh(boolean force) {
        if(tab.equals("usage"))refreshUsage(force);
        if(api.token.isEmpty()||!cacheReady||authenticationExpired)return;
        if(polling){forceRefreshPending|=force;return;}polling=true;forceRefreshPending=false;
        final String requestedChat=chatId,requestedTab=tab;final Api client=api;final int epoch=sessionEpoch;final HistoryRepository repository=history;
        syncModel(chatModels.pending(requestedChat),false);
        final long requestedModelRevision=chatModels.revision(requestedChat);
        final boolean invalidate=liveDirty;final long revision=liveRevision;
        io.execute(()->{try{
            JSONArray list=client.list("/api/v1/chats/?include_folders=true&include_pinned=true");
            repository.saveList(list);
            ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;chats=list;setConnection(true);
                prefetchHistory(list);
                if(tab.equals("chats")&&chatId.isEmpty())renderChatRows(false);
                if(tab.equals("tasks"))renderChatRows(true);
            });
            HistoryRepository.Snapshot detail=null;
            if(!requestedChat.isEmpty()&&requestedTab.equals("chats")){
                try{detail=repository.refresh(requestedChat,HistoryRepository.summary(list,requestedChat),force||invalidate);}
                catch(Api.NotFoundException e){repository.removeChat(requestedChat);ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;cachedChatIds.remove(requestedChat);if(chatId.equals(requestedChat)){chatId="";store.lastChat("");current=new JSONObject();renderPage();}Toast.makeText(this,e.getMessage(),Toast.LENGTH_SHORT).show();});}
            }
            final HistoryRepository.Snapshot d=detail;
            ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;polling=false;
                if(d!=null){cachedChatIds.add(requestedChat);if(chatId.equals(requestedChat)&&tab.equals("chats")){current=d.response;currentSavedAt=d.savedAt;currentVerified=true;observeModel(requestedChat,current,requestedModelRevision);if(revision==liveRevision)liveDirty=false;renderMessages();}}
                updateConnectionLabel();
                if(forceRefreshPending)refresh(true);else if(!requestedChat.equals(chatId)||!requestedTab.equals(tab)||revision!=liveRevision)refresh(false);
            });
        }catch(Exception e){ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;polling=false;currentVerified=false;setConnection(false);renderMessages();if(e instanceof Api.AuthException)expireSession();else if(force)error(e);});}});
    }
    private void prefetchHistory(JSONArray list) {
        if(prefetching||authenticationExpired)return;prefetching=true;
        final HistoryRepository repository=history;final int epoch=sessionEpoch;final String selected=chatId;
        historySync.execute(()->{try{
            // Download missing snapshots before refreshing already saved conversations.
            Set<String> existing=repository.cachedIds();
            for(int pass=0;pass<2;pass++)for(int i=0;i<list.length()&&!repository.closed()&&!Thread.currentThread().isInterrupted();i++){
                JSONObject item=list.optJSONObject(i);if(item==null)continue;String id=item.optString("id");if(id.isEmpty()||id.equals(selected))continue;
                if(existing.contains(id)!=(pass==1)||System.currentTimeMillis()<historyRetryAt.getOrDefault(id,0L))continue;
                try{
                    if(!repository.needsRefresh(id,item))continue;
                    repository.refresh(id,item,false);
                    ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;boolean added=cachedChatIds.add(id);updateConnectionLabel();if(added&&chatId.isEmpty()&&tab.equals("chats"))renderChatRows(false);});
                }catch(Api.NotFoundException e){repository.removeChat(id);}
                catch(Api.AuthException|InterruptedException e){throw e;}
                catch(Exception e){historyRetryAt.put(id,System.currentTimeMillis()+30000);}
            }
        }catch(Exception e){if(e instanceof Api.AuthException)ui.post(()->{if(!isDestroyed()&&epoch==sessionEpoch)expireSession();});}
        finally{ui.post(()->{if(!isDestroyed()&&epoch==sessionEpoch)prefetching=false;});}});
    }
    private void renderPage() {
        if(workspaceView!=null){workspaceView.close();workspaceView=null;}
        body.removeAllViews();messageList=null;chatRows=null;chatStatus=null;composer=null;send=null;modelButton=null;attachmentRows=null;terminalButton=null;
        if(tab.equals("chats")){if(chatId.isEmpty())chatList(false);else chatPage();}
        else if(tab.equals("tasks"))chatList(true);
        else if(tab.equals("workspace"))renderWorkspace();else if(tab.equals("usage")){renderUsage();refreshUsage(false);}else settings();
    }
    private void chatList(boolean tasks) {
        LinearLayout top=horizontal();top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(20),dp(17),dp(20),dp(12));
        LinearLayout titles=vertical();titles.addView(label(tasks?"任务动态":"对话",28,TEXT));titles.addView(label(tasks?"自动同步服务器执行状态":"在手机与电脑之间继续",12,MUTED));top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        if(!tasks){Button b=button("＋ 新对话",true);top.addView(b);b.setOnClickListener(v->newChat());}body.addView(top);
        ScrollView scroll=new ScrollView(this);chatRows=vertical();chatRows.setPadding(dp(16),0,dp(16),dp(16));scroll.addView(chatRows);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));renderChatRows(tasks);
    }
    private void renderChatRows(boolean tasks) {
        if(chatRows==null)return;chatRows.removeAllViews();int active=0;
        for(int i=0;i<chats.length();i++){JSONObject c=chats.optJSONObject(i);if(c!=null&&c.optBoolean("active"))active++;}
        if(tasks){TextView summary=label((connected?"":"上次同步：")+active+" 个任务运行中",19,GREEN);summary.setPadding(dp(4),dp(8),0,dp(18));chatRows.addView(summary);}
        if(chats.length()==0){chatRows.addView(label(connected?"还没有对话，点击新对话开始。":cacheReady?"手机上还没有历史记录，联网后会自动保存。":"正在读取本地记录…",15,MUTED));return;}
        for(int pass=0;pass<(tasks?2:1);pass++)for(int i=0;i<chats.length();i++){
            JSONObject c=chats.optJSONObject(i);if(c==null)continue;boolean running=c.optBoolean("active");if(tasks&&running!=(pass==0))continue;
            LinearLayout card=vertical();card.setPadding(dp(16),dp(16),dp(16),dp(16));card.setBackground(rounded(PANEL,16));
            TextView title=label(c.optString("title","新对话"),17,TEXT);title.setMaxLines(2);card.addView(title);
            String saved=cachedChatIds.contains(c.optString("id"))?" · 已保存到手机":" · 等待保存";
            TextView status=label((running?(connected?"● 运行中":"○ 上次同步时运行中"):"○ "+date(c.optLong("updated_at")))+saved,12,running&&connected?GREEN:MUTED);status.setPadding(0,dp(8),0,0);card.addView(status);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);chatRows.addView(card,lp);card.setOnClickListener(v->openChat(c.optString("id")));
            View.OnLongClickListener rename=v->{renameChat(c.optString("id"),c.optString("title","新对话"));return true;};card.setOnLongClickListener(rename);title.setOnLongClickListener(rename);title.setOnClickListener(v->card.performClick());
        }
    }
    private void renameChat(String id,String oldTitle){
        if(id.isEmpty())return;if(authenticationExpired){Toast.makeText(this,"请先到设置重新登录后修改名称。",Toast.LENGTH_LONG).show();return;}
        LinearLayout content=vertical();content.setPadding(dp(20),dp(8),dp(20),dp(4));
        EditText name=UiStyle.field(this,oldTitle);name.setSingleLine(true);name.setHint("对话名称");name.setSelectAllOnFocus(true);name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);content.addView(name,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("编辑对话名称").setView(content).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(ignored->{
            Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE),cancel=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);save.setTextColor(GREEN);cancel.setTextColor(MUTED);
            Runnable restore=()->{if(!dialog.isShowing())return;save.setEnabled(true);save.setText("保存");cancel.setEnabled(true);name.setEnabled(true);dialog.setCancelable(true);name.requestFocus();};
            save.setOnClickListener(v->{
                String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("请输入对话名称");name.requestFocus();return;}if(title.equals(oldTitle)){dialog.dismiss();return;}
                save.setEnabled(false);save.setText("保存中…");cancel.setEnabled(false);name.setEnabled(false);dialog.setCancelable(false);
                final Api client=api;final HistoryRepository repository=history;
                run(()->{JSONObject result=client.renameChat(id,title);String saved=result.getJSONObject("chat").optString("title",title);repository.renameTitle(id,saved);return saved;},value->{
                    String saved=(String)value;JSONObject item=HistoryRepository.summary(chats,id);if(item!=null)item.put("title",saved);
                    if(id.equals(current.optString("id"))&&current.optJSONObject("chat")!=null){current.put("title",saved);current.getJSONObject("chat").put("title",saved);}
                    dialog.dismiss();if(tab.equals("tasks"))renderChatRows(true);else if(tab.equals("chats")){if(chatId.isEmpty())renderChatRows(false);else if(chatId.equals(id))renderMessages();}refresh(false);
                },true,restore);
            });
            name.setOnEditorActionListener((view,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){if(save.isEnabled())save.performClick();return true;}return false;});
            name.requestFocus();name.selectAll();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });dialog.show();
    }
    private void newChat() {
        new AlertDialog.Builder(this).setTitle("新建对话").setMessage("选择模型后即可发送。对话会与电脑端同步。")
            .setPositiveButton("新建",(dialog,which)->{
                final String initialModel=initialModel();
                run(()->api.post("/api/v1/chats/new",new JSONObject().put("chat",new JSONObject().put("title","手机新对话").put("models",new JSONArray().put(initialModel)).put("history",new JSONObject().put("currentId",JSONObject.NULL).put("messages",new JSONObject())).put("messages",new JSONArray()).put("timestamp",System.currentTimeMillis()))),v->openChat(((JSONObject)v).optString("id")));
            }).setNegativeButton("取消",null).show();
    }
    private String initialModel(){for(int i=0;i<models.length();i++){JSONObject model=models.optJSONObject(i);if(model!=null&&ChatModelSelection.DEFAULT.equals(model.optString("id")))return ChatModelSelection.DEFAULT;}JSONObject first=models.optJSONObject(0);return first==null?ChatModelSelection.DEFAULT:first.optString("id",ChatModelSelection.DEFAULT);}
    private void openChat(String id) {
        rememberDraft();chatId=id;store.lastChat(id);current=new JSONObject();currentSavedAt=0;currentVerified=false;localHistoryLoading=true;fingerprint="";tab="chats";restoreTerminal();renderNavigation();renderPage();
        final HistoryRepository repository=history;final int epoch=sessionEpoch;
        final long requestedModelRevision=chatModels.revision(id);
        localReads.execute(()->{try{
            HistoryRepository.Snapshot snapshot=repository.cached(id);
            ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch||!id.equals(chatId))return;localHistoryLoading=false;if(snapshot!=null&&snapshot.savedAt>=currentSavedAt){current=snapshot.response;currentSavedAt=snapshot.savedAt;observeModel(id,current,requestedModelRevision);}renderMessages();refresh(false);});
        }catch(Exception e){ui.post(()->{if(!isDestroyed()&&epoch==sessionEpoch&&id.equals(chatId)){localHistoryLoading=false;renderMessages();refresh(false);}});}});
    }
    private void chatPage() {
        LinearLayout toolbar=horizontal();toolbar.setPadding(dp(12),dp(3),dp(12),dp(8));toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("‹ 对话",false);toolbar.addView(back);back.setOnClickListener(v->{rememberDraft();chatId="";store.lastChat("");current=new JSONObject();renderPage();refresh(false);});
        modelButton=button(modelName(chatModels.selected(chatId))+" ▾",false);modelButton.setTextSize(12);toolbar.addView(modelButton,new LinearLayout.LayoutParams(0,dp(44),1));modelButton.setOnClickListener(v->chooseModel());
        Button stop=button("停止",false);stop.setTextColor(RED);toolbar.addView(stop);stop.setOnClickListener(v->stopTask());body.addView(toolbar);
        LinearLayout tools=horizontal();tools.setPadding(dp(12),0,dp(12),0);terminalButton=button(terminalLabel(),false);terminalButton.setTextSize(12);tools.addView(terminalButton,new LinearLayout.LayoutParams(0,dp(40),1));terminalButton.setOnClickListener(v->chooseTerminal());Button workspaceButton=button("工作区 ↗",false);workspaceButton.setTextSize(12);tools.addView(workspaceButton);workspaceButton.setOnClickListener(v->{rememberDraft();tab="workspace";renderNavigation();renderPage();});body.addView(tools);
        chatStatus=label("正在读取对话…",12,MUTED);chatStatus.setPadding(dp(20),0,dp(20),dp(8));chatStatus.setOnLongClickListener(v->{JSONObject summary=HistoryRepository.summary(chats,chatId);JSONObject chat=current.optJSONObject("chat");renameChat(chatId,summary!=null?summary.optString("title","新对话"):chat==null?"新对话":chat.optString("title","新对话"));return true;});body.addView(chatStatus);
        messageScroll=new ScrollView(this);messageScroll.setFillViewport(true);messageList=vertical();messageList.setPadding(dp(16),dp(8),dp(16),dp(20));messageScroll.addView(messageList);body.addView(messageScroll,new LinearLayout.LayoutParams(-1,0,1));
        attachmentRows=vertical();attachmentRows.setPadding(dp(16),0,dp(16),0);ScrollView attachmentScroll=new ScrollView(this);attachmentScroll.addView(attachmentRows);body.addView(attachmentScroll,new LinearLayout.LayoutParams(-1,-2));renderAttachments();
        LinearLayout compose=horizontal();compose.setBaselineAligned(false);compose.setGravity(Gravity.BOTTOM);compose.setPadding(dp(12),dp(8),dp(12),dp(10));
        ImageButton attach=new ImageButton(this);attach.setImageResource(R.drawable.ic_add_attachment);attach.setContentDescription("上传附件");attach.setPadding(dp(12),dp(12),dp(12),dp(12));attach.setBackgroundTintList(null);attach.setBackground(UiStyle.surface(this,PANEL,12,true));LinearLayout.LayoutParams attachLp=new LinearLayout.LayoutParams(dp(48),dp(48));attachLp.rightMargin=dp(8);compose.addView(attach,attachLp);attach.setOnClickListener(v->pickFiles(false));
        composer=field("发送消息…",drafts.getOrDefault(chatId,""),false);composer.setSingleLine(false);composer.setMaxLines(5);composer.setMinLines(1);composer.setMinHeight(dp(48));composer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1));send=button("发送",true);LinearLayout.LayoutParams sendLp=new LinearLayout.LayoutParams(dp(70),dp(48));sendLp.leftMargin=dp(8);compose.addView(send,sendLp);send.setOnClickListener(v->sendMessage());final Button sendButton=send;
        composer.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{int height=bottom-top;if(height<=0)return;if(attach.getLayoutParams().height!=height){attach.getLayoutParams().height=height;attach.requestLayout();}if(sendButton.getLayoutParams().height!=height){sendButton.getLayoutParams().height=height;sendButton.requestLayout();}});
        body.addView(compose);fingerprint="";renderMessages();
    }
    private void rememberDraft() {if(composer!=null&&!chatId.isEmpty())drafts.put(chatId,composer.getText().toString());}
    private String terminalName(String id) {for(int i=0;i<terminals.length();i++){JSONObject item=terminals.optJSONObject(i);if(item!=null&&id.equals(item.optString("id")))return item.optString("name","OpenTerminal");}return "OpenTerminal";}
    private String terminalLabel(){return workspace.terminal.isEmpty()?"终端：未启用 ▾":"终端："+terminalName(workspace.terminal)+" ▾";}
    private void restoreTerminal(){JSONObject saved=preferences.optJSONObject(chatId.isEmpty()?"workspace":"chat:"+chatId);workspace.terminal=saved==null?"":saved.optString("terminal");workspace.directory=saved==null?"/":saved.optString("directory","/");}
    private void saveTerminal(){
        try{String key=chatId.isEmpty()?"workspace":"chat:"+chatId;JSONObject entry=preferences.optJSONObject(key);if(entry==null){entry=new JSONObject();preferences.put(key,entry);}entry.put("terminal",workspace.terminal).put("directory",workspace.directory);
            savePreferences();
        }catch(Exception e){error(e);}
        if(terminalButton!=null)terminalButton.setText(terminalLabel());
    }
    private void loadTerminals(boolean choose){
        if(authenticationExpired){if(choose)Toast.makeText(this,"请到设置重新登录后连接终端。",Toast.LENGTH_LONG).show();return;}
        final Api client=api;run(()->new TerminalApi(client).servers(),value->{terminals=(JSONArray)value;if(terminalButton!=null)terminalButton.setText(terminalLabel());if(choose)terminalDialog();},choose);
    }
    private void chooseTerminal(){if(terminals.length()==0)loadTerminals(true);else terminalDialog();}
    private void terminalDialog(){
        if(terminals.length()==0){new AlertDialog.Builder(this).setTitle("没有可用的 OpenTerminal").setMessage("请在电脑端管理员面板确认终端连接已启用，并允许当前账号访问。").setPositiveButton("知道了",null).show();return;}
        String[] labels=new String[terminals.length()+1];labels[0]="不在当前对话中使用终端";int selected=0;
        for(int i=0;i<terminals.length();i++){JSONObject item=terminals.optJSONObject(i);labels[i+1]=item==null?"OpenTerminal":item.optString("name","OpenTerminal");if(item!=null&&workspace.terminal.equals(item.optString("id")))selected=i+1;}
        new AlertDialog.Builder(this).setTitle("当前对话的 OpenTerminal").setSingleChoiceItems(labels,selected,(dialog,which)->{
            String next=which==0?"":terminals.optJSONObject(which-1).optString("id");
            JSONArray files=workspaceAttachments.get(chatId);if(files!=null&&files.length()>0&&!next.equals(workspace.terminal)){Toast.makeText(this,"请先移除当前终端的文件附件，再切换终端。",Toast.LENGTH_LONG).show();return;}
            workspace.terminal=next;saveTerminal();dialog.dismiss();if(tab.equals("workspace"))renderPage();
        }).setNegativeButton("取消",null).show();
    }
    private void renderWorkspace(){
        final Api client=api.session(chatId);workspaceView=new WorkspaceView(this,client,workspace,workspace.terminal.isEmpty()?"":terminalName(workspace.terminal),new WorkspaceView.Host(){
            @Override public void run(Job job,Result result){MainActivity.this.run(job,result);}
            @Override public void run(Job job,Result result,Runnable failed){MainActivity.this.run(job,result,true,failed);}
            @Override public void chooseTerminal(){MainActivity.this.chooseTerminal();}
            @Override public void directoryChanged(){saveTerminal();}
            @Override public void upload(){pickFiles(true);}
        });body.addView(workspaceView,new LinearLayout.LayoutParams(-1,-1));
    }
    private void pickFiles(boolean toWorkspace){
        if(authenticationExpired){Toast.makeText(this,"请先重新登录",Toast.LENGTH_SHORT).show();return;}
        if(toWorkspace&&workspace.terminal.isEmpty()){chooseTerminal();return;}
        if(!toWorkspace&&sending){Toast.makeText(this,"请等待当前消息提交完成",Toast.LENGTH_SHORT).show();return;}
        pickerChat=chatId;pickerEpoch=sessionEpoch;pickerWorkspace=toWorkspace;pickerTerminal=workspace.terminal;pickerDirectory=workspace.directory;
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{startActivityForResult(picker,42);}catch(Exception e){error(e);}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=42||result!=RESULT_OK||data==null||pickerEpoch!=sessionEpoch)return;
        List<Uri> selected=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)selected.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)selected.add(data.getData());
        for(Uri uri:selected){if(!"content".equals(uri.getScheme()))continue;try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            Attachment item=new Attachment(getContentResolver(),uri);if(!pickerWorkspace)attachments.computeIfAbsent(pickerChat,key->new ArrayList<>()).add(item);uploadAttachment(item,pickerChat,pickerWorkspace,pickerTerminal,pickerDirectory);
        }renderAttachments();
    }
    private void uploadAttachment(Attachment item,String id,boolean toWorkspace,String terminalId,String directory){
        final Api client=api.session(id);final int epoch=sessionEpoch;item.busy=true;item.error="";item.status="正在上传";
        uploads.execute(()->{try{
            if(Thread.currentThread().isInterrupted()||epoch!=sessionEpoch||isDestroyed())return;
            String route=toWorkspace?new TerminalApi(client).path(terminalId,"/files/upload?directory="+Api.query(directory)):"/api/v1/files/?process="+(!item.mime.startsWith("image/"));
            String name=item.name;
            if(toWorkspace){JSONArray entries=new TerminalApi(client).files(terminalId,directory).getJSONArray("entries");Set<String> names=new HashSet<>();for(int i=0;i<entries.length();i++)names.add(entries.getJSONObject(i).optString("name"));int dot=name.lastIndexOf('.');String stem=dot>0?name.substring(0,dot):name,extension=dot>0?name.substring(dot):"";for(int n=1;names.contains(name);n++)name=stem+" ("+n+")"+extension;}
            final String uploadName=name;
            JSONObject result=client.upload(route,getContentResolver(),item.uri,uploadName,item.mime,item.size,sent->ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;item.status=item.size>0?"上传 "+Math.min(100,sent*100/item.size)+"%":"上传 "+sent/1024+" KB";renderAttachments();}));
            if(!toWorkspace&&!item.mime.startsWith("image/")){
                String fileId=result.optString("id");if(fileId.isEmpty())throw new Exception("服务器没有返回附件编号");
                ui.post(()->{if(!isDestroyed()&&epoch==sessionEpoch){item.status="正在解析文件";renderAttachments();}});
                long deadline=System.currentTimeMillis()+180000;boolean complete=false;
                while(System.currentTimeMillis()<deadline){if(Thread.currentThread().isInterrupted()||epoch!=sessionEpoch||isDestroyed())throw new InterruptedException();
                    JSONObject state=client.get("/api/v1/files/"+Api.query(fileId)+"/process/status");String status=state.optString("status");
                    if("completed".equals(status)){complete=true;break;}if("failed".equals(status)||"error".equals(status))throw new Exception(state.optString("error","服务器无法解析此文件，可尝试上传到工作区后由终端处理。"));Thread.sleep(1000);
                }if(!complete)throw new Exception("文件解析超时，请稍后重试，或上传到工作区处理。");
            }
            JSONObject descriptor=toWorkspace?null:item.descriptor(result);
            ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;item.busy=false;item.uploaded=descriptor;item.status="已上传";
                if(toWorkspace){Toast.makeText(this,"已上传到工作区："+uploadName,Toast.LENGTH_LONG).show();if(tab.equals("workspace")&&workspaceView!=null&&workspace.terminal.equals(terminalId)&&chatId.equals(id))workspaceView.uploaded(directory,uploadName);}else renderAttachments();
            });
        }catch(Exception e){ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;item.busy=false;item.error=e.getMessage()==null?"上传失败":e.getMessage();item.status="上传失败";renderAttachments();if(e instanceof Api.AuthException)expireSession();else if(toWorkspace)error(e);});}});
    }
    private void renderAttachments(){
        if(attachmentRows==null)return;attachmentRows.removeAllViews();
        List<Attachment> files=attachments.get(chatId);if(files!=null)for(Attachment item:new ArrayList<>(files)){
            LinearLayout row=horizontal();TextView label=label("📎 "+item.name+" · "+(item.error.isEmpty()?item.status:item.error),11,item.error.isEmpty()?GREEN:RED);label.setMaxLines(2);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            if(!item.busy&&!item.error.isEmpty()){Button retry=button("重试",false);retry.setTextSize(11);row.addView(retry);retry.setOnClickListener(v->{uploadAttachment(item,chatId,false,"","");renderAttachments();});}
            Button remove=button("×",false);remove.setContentDescription("移除附件 "+item.name);row.addView(remove,new LinearLayout.LayoutParams(dp(40),dp(35)));remove.setEnabled(!sending);remove.setOnClickListener(v->{files.remove(item);renderAttachments();});attachmentRows.addView(row);
        }
        JSONArray remote=workspaceAttachments.get(chatId);if(remote!=null)for(int i=0;i<remote.length();i++){JSONObject file=remote.optJSONObject(i);if(file==null)continue;final int index=i;LinearLayout row=horizontal();row.addView(label("📎 工作区 · "+file.optString("name"),11,GREEN),new LinearLayout.LayoutParams(0,-2,1));Button remove=button("×",false);row.addView(remove,new LinearLayout.LayoutParams(dp(40),dp(35)));remove.setEnabled(!sending);remove.setOnClickListener(v->{remote.remove(index);renderAttachments();});attachmentRows.addView(row);}
    }
    private String modelName(String id) {
        for(int i=0;i<models.length();i++){JSONObject m=models.optJSONObject(i);if(m!=null&&m.optString("id").equals(id))return m.optString("name",id);}return id;
    }
    private void chooseModel() {
        if(models.length()==0){Toast.makeText(this,"模型列表仍在加载，请重试",Toast.LENGTH_SHORT).show();return;}
        String[] labels=new String[models.length()],ids=new String[models.length()];for(int i=0;i<labels.length;i++){ids[i]=models.optJSONObject(i).optString("id");labels[i]=models.optJSONObject(i).optString("name",ids[i]);}
        final String id=chatId;
        new AlertDialog.Builder(this).setTitle("选择此对话的模型").setItems(labels,(dialog,which)->selectModel(id,ids[which])).show();
    }
    private void updateModelButton(){if(modelButton!=null)modelButton.setText(modelName(chatModels.selected(chatId))+" ▾");}
    private void savePreferences()throws Exception{
        final JSONObject saved=new JSONObject(preferences.toString());final HistoryRepository repository=history;
        uploads.execute(()->{try{repository.preferences(saved);}catch(Exception ignored){}});
    }
    private void observeModel(String id,JSONObject response,long revision){try{if(chatModels.observe(id,response,revision))savePreferences();updateModelButton();}catch(Exception e){error(e);}}
    private void selectModel(String id,String model){
        try{ChatModelSelection.Change change=chatModels.choose(id,model);savePreferences();updateModelButton();syncModel(change,true);}catch(Exception e){error(e);}
    }
    private void syncModel(ChatModelSelection.Change change,boolean userInitiated){
        if(change==null||authenticationExpired||api.token.isEmpty()||history==null)return;
        if(Long.valueOf(change.revision).equals(modelSyncs.get(change.chatId))||(!userInitiated&&System.currentTimeMillis()<modelRetryAt.getOrDefault(change.chatId,0L)))return;
        modelSyncs.put(change.chatId,change.revision);
        final Api client=api;final HistoryRepository repository=history;final int epoch=sessionEpoch;
        modelWrites.execute(()->{try{
            if(epoch!=sessionEpoch||repository.closed())return;
            client.selectChatModel(change.chatId,change.model);repository.selectModel(change.chatId,change.model);
            ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;if(Long.valueOf(change.revision).equals(modelSyncs.get(change.chatId)))modelSyncs.remove(change.chatId);try{if(chatModels.confirm(change))savePreferences();}catch(Exception e){error(e);}updateModelButton();});
        }catch(Exception e){ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;if(Long.valueOf(change.revision).equals(modelSyncs.get(change.chatId)))modelSyncs.remove(change.chatId);modelRetryAt.put(change.chatId,System.currentTimeMillis()+10000);if(e instanceof Api.AuthException)expireSession();else if(userInitiated)Toast.makeText(this,"模型选择已保存在此对话，联网后重试同步。",Toast.LENGTH_LONG).show();});}});
    }
    private void stopTask() {
        final String id=chatId;if(id.isEmpty())return;
        new AlertDialog.Builder(this).setTitle("停止当前对话任务？").setMessage("服务器上的这次生成将被中止。")
            .setNegativeButton("继续运行",null).setPositiveButton("停止任务",(d,w)->run(()->api.post("/api/tasks/chat/"+id+"/stop",new JSONObject()),v->{Toast.makeText(this,"已发送停止请求",Toast.LENGTH_SHORT).show();refresh(true);})).show();
    }
    private void sendMessage() {
        if(sending||composer==null)return;String text=composer.getText().toString().trim();
        final JSONArray files=new JSONArray();List<Attachment> pending=attachments.get(chatId);if(pending!=null)for(Attachment item:pending){if(item.busy||item.uploaded==null){Toast.makeText(this,"请等待附件上传完成，或移除上传失败的附件。",Toast.LENGTH_LONG).show();return;}files.put(item.uploaded);}
        JSONArray remote=workspaceAttachments.get(chatId);if(remote!=null)for(int i=0;i<remote.length();i++)files.put(remote.opt(i));
        if(text.isEmpty()&&files.length()==0)return;if(text.isEmpty())text="请查看附件。";
        if(!connected||authenticationExpired||!currentVerified){Toast.makeText(this,authenticationExpired?"请先到设置重新登录":"正在同步最新对话，请联网后再发送。",Toast.LENGTH_SHORT).show();refresh(false);return;}
        if(ChatData.running(current)){Toast.makeText(this,"当前任务运行中，可切换到其他对话继续。",Toast.LENGTH_LONG).show();return;}
        if(current.optString("id").isEmpty()){Toast.makeText(this,"请等待对话加载完成",Toast.LENGTH_SHORT).show();return;}
        if(live==null||!live.connected()){ensureLive();Toast.makeText(this,"正在建立实时会话，请稍后发送。",Toast.LENGTH_SHORT).show();return;}
        final String clientSessionId=live.id();
        final String id=chatId,selectedModel=chatModels.selected(id),terminalId=workspace.terminal,directory=workspace.directory;final Api client=api;final JSONObject snapshot=current;final String content=text;
        sending=true;send.setEnabled(false);send.setText("提交中");renderAttachments();
        run(()->{
            JSONObject request=CompletionRequest.build(snapshot,id,selectedModel,clientSessionId,content,files,terminalId);
            if(!terminalId.isEmpty())new TerminalApi(client.session(id)).cwd(terminalId,directory);
            return client.post("/api/chat/completions",request);
        },v->{
            sending=false;liveDirty=true;liveRevision++;drafts.remove(id);attachments.remove(id);workspaceAttachments.remove(id);if(chatId.equals(id)&&composer!=null){composer.setText("");renderAttachments();send.setEnabled(true);send.setText("发送");chatStatus.setText("● 任务已提交，正在服务器执行");}refresh(true);
        },true,()->{sending=false;if(send!=null){send.setEnabled(true);send.setText("发送");}refresh(true);});
    }
    private void renderMessages() {
        if(messageList==null)return;
        boolean running=ChatData.running(current);JSONObject chat=ChatData.object(current,"chat");
        boolean available=current.optJSONObject("chat")!=null;
        if(chatStatus!=null){chatStatus.setText(available?(!connected||!currentVerified?"✓ 此对话已保存在手机 · ":running?"● 运行中 · ":"✓ 已同步 · ")+chat.optString("title","对话"):localHistoryLoading?"正在读取此对话的手机记录…":"此对话尚未保存到手机，联网后自动读取。");chatStatus.setTextColor(running&&connected&&currentVerified?GREEN:MUTED);}
        String signature=current.toString()+"|"+(connected&&currentVerified);if(signature.equals(fingerprint))return;fingerprint=signature;
        int oldY=messageScroll.getScrollY();boolean bottom=messageList.getChildCount()==0||messageList.getHeight()-messageScroll.getHeight()-oldY<dp(110);
        messageList.removeAllViews();List<JSONObject> messages=ChatData.messages(current);
        if(messages.isEmpty()){messageList.addView(label(available?"从这里开始。\n消息与执行过程会同步到电脑端。":"此对话尚未保存到手机。\n请联网完成首次同步。",17,MUTED));return;}
        for(JSONObject message:messages)renderMessage(message);
        messageScroll.post(()->{if(bottom)messageScroll.fullScroll(View.FOCUS_DOWN);else messageScroll.scrollTo(0,oldY);});
    }
    private void renderMessage(JSONObject message) {
        boolean user=message.optString("role").equals("user");LinearLayout bubble=vertical();bubble.setPadding(dp(14),dp(13),dp(14),dp(14));bubble.setBackground(rounded(user?0xff243b3d:PANEL,16));
        TextView byline=label(user?"你":modelName(message.optString("model","Assistant")),11,user?GREEN:MUTED);bubble.addView(byline);space(bubble,7);
        JSONArray files=message.optJSONArray("files");if(files!=null)for(int i=0;i<files.length();i++){JSONObject file=files.optJSONObject(i);if(file!=null){TextView attachment=label("📎 "+file.optString("name",file.optString("filename","附件")),12,GREEN);attachment.setPadding(0,0,0,dp(7));attachment.setTextIsSelectable(true);bubble.addView(attachment);}}
        JSONArray output=message.optJSONArray("output");boolean renderedText=false;
        if(output!=null&&!user){
            Map<String,JSONObject> toolOutputs=new HashMap<>();for(int i=0;i<output.length();i++){JSONObject o=output.optJSONObject(i);if(o!=null&&o.optString("type").equals("function_call_output"))toolOutputs.put(o.optString("call_id"),o);}
            for(int i=0;i<output.length();i++){
                JSONObject item=output.optJSONObject(i);if(item==null)continue;String type=item.optString("type");
                if(type.equals("message")){String text=ChatData.text(item.opt("content"));if(!text.isEmpty()){markdown(bubble,text);renderedText=true;}}
                else if(type.equals("open_webui:code_interpreter")){toolCard(bubble,item.optString("id",message.optString("id")+i),item.optString("name","Bash"),item.optString("lang","bash"),item.optString("code"),outputText(item.opt("output")),item.optString("status","in_progress"));}
                else if(type.equals("function_call")){
                    JSONObject args=item.optJSONObject("arguments");if(args==null){try{args=new JSONObject(item.optString("arguments","{}"));}catch(Exception e){args=new JSONObject();}}
                    JSONObject out=toolOutputs.get(item.optString("call_id"));String command=args.optString("command",args.optString("cmd",""));
                    toolCard(bubble,item.optString("call_id"),args.optString("title",item.optString("name","Tool")),command.isEmpty()?"json":"bash",command.isEmpty()?args.toString():command,out==null?"":outputText(out.opt("output")),item.optString("status","in_progress"));
                }else if(type.equals("reasoning")){String text=ChatData.text(item.opt("summary"));if(text.isEmpty())text=ChatData.text(item.opt("content"));if(!text.isEmpty())toolCard(bubble,item.optString("id"),"思考过程","text",text,"",item.optString("status","completed"));}
            }
        }
        String content=ChatData.text(message.opt("content"));if(!renderedText&&!content.isEmpty())markdown(bubble,content);
        if(!user&&!message.optBoolean("done",true)){TextView progress=label(connected&&currentVerified?"● 正在执行…":"○ 上次同步时正在执行",12,connected&&currentVerified?GREEN:MUTED);progress.setPadding(0,dp(10),0,0);bubble.addView(progress);}
        if(message.has("error")&&!message.isNull("error")){bubble.addView(label("执行错误："+ChatData.text(message.opt("error")),12,RED));}
        JSONObject u=message.optJSONObject("usage");if(u!=null&&u.has("total_tokens")){TextView count=label(number(u.optLong("total_tokens"))+" tokens",10,MUTED);count.setPadding(0,dp(10),0,0);bubble.addView(count);}
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);if(user)lp.leftMargin=dp(25);else lp.rightMargin=dp(6);messageList.addView(bubble,lp);
    }
    private String outputText(Object value) {
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;StringBuilder result=new StringBuilder();for(String key:new String[]{"stdout","stderr","result","output","error"}){String t=ChatData.text(o.opt(key));if(!t.isEmpty()){if(result.length()>0)result.append('\n');result.append(t);}}return result.length()>0?result.toString():o.toString();}
        return ChatData.text(value);
    }
    private void toolCard(LinearLayout parent,String key,String name,String lang,String code,String output,String status) {
        LinearLayout card=vertical();card.setBackground(rounded(0xff101924,10));card.setPadding(dp(12),dp(10),dp(12),dp(10));
        boolean complete=status.equals("completed"),failed=status.equals("failed")||status.equals("incomplete");
        TextView heading=label((expanded.contains(key)?"▾ ":"▸ ")+name+"  ·  "+(failed?"失败":complete?"已完成":"运行中"),13,failed?RED:complete?MUTED:GREEN);card.addView(heading);
        LinearLayout details=vertical();details.setVisibility(expanded.contains(key)?View.VISIBLE:View.GONE);card.addView(details);
        if(expanded.contains(key))toolDetails(details,code,lang,output);
        heading.setPadding(0,dp(5),0,dp(5));heading.setOnClickListener(v->{if(expanded.contains(key))expanded.remove(key);else expanded.add(key);if(expanded.contains(key)&&details.getChildCount()==0)toolDetails(details,code,lang,output);details.setVisibility(expanded.contains(key)?View.VISIBLE:View.GONE);heading.setText((expanded.contains(key)?"▾ ":"▸ ")+name+"  ·  "+(failed?"失败":complete?"已完成":"运行中"));});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);lp.bottomMargin=dp(6);parent.addView(card,lp);
    }
    private void toolDetails(LinearLayout details,String code,String lang,String output) {
        output=TerminalText.plain(output);
        if(!code.isEmpty()){space(details,10);codeBlock(details,code,lang);}
        if(!output.isEmpty()){space(details,9);details.addView(label("输出",10,MUTED));codeBlock(details,output,"text");}
        if(code.isEmpty()&&output.isEmpty())details.addView(label("等待服务器返回执行数据…",12,MUTED));
    }
    private void markdown(LinearLayout parent,String value) {
        Pattern fence=Pattern.compile("```([^\\n`]*)\\n([\\s\\S]*?)(?:```|$)");Matcher matcher=fence.matcher(value);int last=0;
        while(matcher.find()){if(matcher.start()>last)paragraph(parent,value.substring(last,matcher.start()));codeBlock(parent,matcher.group(2),matcher.group(1).trim());last=matcher.end();}
        if(last<value.length())paragraph(parent,value.substring(last));
    }
    private void paragraph(LinearLayout parent,String value) {
        String clean=value.trim();if(clean.isEmpty())return;
        TextView text=label(clean,15,TEXT);text.setTextIsSelectable(true);text.setLineSpacing(dp(4),1.0f);text.setPadding(0,dp(4),0,dp(6));
        // Keep Markdown readable without executing HTML from model output.
        SpannableString span=new SpannableString(clean);Matcher bold=Pattern.compile("\\*\\*(.+?)\\*\\*").matcher(clean);while(bold.find())span.setSpan(new StyleSpan(Typeface.BOLD),bold.start(),bold.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setText(span);parent.addView(text);
    }
    private void codeBlock(LinearLayout parent,String code,String language) {
        LinearLayout box=vertical();box.setPadding(dp(10),dp(6),dp(10),dp(10));box.setBackground(rounded(0xff0c121c,8));
        LinearLayout bar=horizontal();bar.setGravity(Gravity.CENTER_VERTICAL);bar.addView(label(language.isEmpty()?"code":language,10,MUTED),new LinearLayout.LayoutParams(0,-2,1));
        TextView copy=label("复制",11,GREEN);copy.setPadding(dp(10),dp(6),dp(5),dp(6));bar.addView(copy);copy.setOnClickListener(v->{((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("代码",code));Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show();});box.addView(bar);
        HorizontalScrollView scroll=new HorizontalScrollView(this);TextView text=label(code,12,0xffd0e6e0);text.setTypeface(Typeface.MONOSPACE);text.setTextIsSelectable(true);text.setHorizontallyScrolling(true);
        if(!language.equals("text")&&code.length()<120000){
            SpannableString highlighted=new SpannableString(code);
            colorMatches(highlighted,"\\b(if|else|elif|then|fi|for|while|do|done|return|def|class|import|from|const|let|var|function|true|false|null|sudo|export)\\b",0xffc5adf5);
            colorMatches(highlighted,"\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'",0xffe7cb91);
            colorMatches(highlighted,"(?m)#.*$",0xff6f8c92);text.setText(highlighted);
        }
        scroll.addView(text);box.addView(scroll);parent.addView(box,new LinearLayout.LayoutParams(-1,-2));
    }
    private void colorMatches(SpannableString text,String pattern,int color){Matcher m=Pattern.compile(pattern).matcher(text);while(m.find())text.setSpan(new ForegroundColorSpan(color),m.start(),m.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
    private void refreshUsage(boolean force){
        if(!cacheReady||authenticationExpired||api.token.isEmpty()||usageCache==null)return;
        final UsageCache stats=usageCache;if(!stats.begin(force))return;usageLoading=true;usageError="";if(tab.equals("usage"))renderUsage();final int epoch=sessionEpoch;
        usageSync.execute(()->{try{JSONObject result=stats.fetch();ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;usage=result;usageLoading=false;usageError="";if(tab.equals("usage"))renderUsage();});}
        catch(Exception e){ui.post(()->{if(isDestroyed()||epoch!=sessionEpoch)return;usageLoading=false;usageError="连接失败，保留上次统计";if(e instanceof Api.AuthException)expireSession();if(tab.equals("usage"))renderUsage();});}});
    }
    private void renderUsage() {
        body.removeAllViews();ScrollView scroll=new ScrollView(this);LinearLayout page=vertical();page.setPadding(dp(20),dp(18),dp(20),dp(25));scroll.addView(page);body.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        page.addView(label("用量",28,TEXT));long saved=usageCache==null?0:usageCache.savedAt();String status=usageLoading?"正在后台更新":!usageError.isEmpty()?usageError:saved>0?"已保存 · "+new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(saved)):"尚无已保存统计";page.addView(label("服务器真实统计 · "+status,12,MUTED));space(page,22);
        JSONObject totals=usage.optJSONObject("totals");if(totals==null){page.addView(label(usageLoading?"正在读取统计…":authenticationExpired?"登录后读取统计":"暂无手机缓存，联网后自动读取；可点击顶部刷新。",16,MUTED));return;}
        LinearLayout hero=vertical();hero.setBackground(rounded(0xff203934,18));hero.setPadding(dp(20),dp(20),dp(20),dp(20));hero.addView(label("累计 Tokens",12,GREEN));TextView total=label(number(totals.optLong("lifetime_tokens")),36,TEXT);total.setTypeface(null,Typeface.BOLD);hero.addView(total);hero.addView(label("输入 "+number(totals.optLong("input_tokens"))+"   /   输出 "+number(totals.optLong("output_tokens")),12,MUTED));page.addView(hero);space(page,15);
        LinearLayout row=horizontal();metric(row,"对话",totals.optLong("total_chats"));metric(row,"消息 · 近30天",totals.optLong("messages"));metric(row,"模型",totals.optLong("models_used"));page.addView(row);space(page,24);
        page.addView(label("每日 Token 趋势",17,TEXT));space(page,12);JSONArray heat=usage.optJSONArray("heatmap");page.addView(new UsageChart(this,heat),new LinearLayout.LayoutParams(-1,dp(115)));space(page,7);page.addView(label("最近 30 天                                      今天",10,MUTED));space(page,24);
        page.addView(label("模型用量 · 最近30天",17,TEXT));JSONArray top=usage.optJSONArray("top_models");
        if(top!=null)for(int i=0;i<top.length();i++){JSONObject m=top.optJSONObject(i);if(m==null)continue;LinearLayout card=vertical();card.setPadding(0,dp(13),0,dp(10));card.addView(label(modelName(m.optString("model_id",m.optString("model","模型"))),14,TEXT));card.addView(label(number(m.optLong("total_tokens"))+" tokens   ·   "+number(m.optLong("message_count",m.optLong("messages")))+" 条消息",12,GREEN));page.addView(card);}
        space(page,12);page.addView(label("仅显示服务器记录到的用量。历史缺失记录不会通过字数估算。",11,MUTED));
    }
    private void metric(LinearLayout row,String title,long value){LinearLayout card=vertical();card.setPadding(dp(9),dp(12),dp(9),dp(12));card.setBackground(rounded(PANEL,12));card.addView(label(number(value),22,TEXT));card.addView(label(title,10,MUTED));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=dp(6);row.addView(card,p);}
    private void settings(){
        ScrollView scroll=new ScrollView(this);LinearLayout page=vertical();page.setPadding(dp(22),dp(20),dp(22),dp(20));scroll.addView(page);body.addView(scroll);page.addView(label("设置",28,TEXT));space(page,22);page.addView(label("服务器",12,MUTED));page.addView(label(api.base,17,TEXT));space(page,18);page.addView(label("账号",12,MUTED));page.addView(label(store.email(),16,TEXT));space(page,24);
        page.addView(label("连接方式\n自定义 HTTPS 服务器或可信私人网络\n\n历史消息\n自动保存到手机，离线可查看。首次联网会在后台保存历史会话，后续按更新时间同步变化。\n\n任务同步\n前台每 2.5 秒检查状态，退出应用后任务继续在服务器运行。\n\n版本\nOpenPocket "+BuildConfig.VERSION_NAME,14,MUTED));space(page,25);
        if(authenticationExpired){Button relogin=button("重新登录",true);page.addView(relogin);relogin.setOnClickListener(v->{sessionEpoch++;if(history!=null){history.close(false);history=null;}api=new Api(store.server(),"");cacheReady=false;polling=false;prefetching=false;chatId="";store.lastChat("");current=new JSONObject();chats=new JSONArray();models=new JSONArray();cachedChatIds.clear();drafts.clear();expanded.clear();composer=null;login();});}
        Button signout=button("退出登录 / 更换服务器",false);signout.setTextColor(RED);page.addView(signout);signout.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("退出登录？").setMessage("会移除手机上的登录令牌和本账号的本地历史记录，服务器上的消息与任务保留。").setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->logout()).show());
    }
    @Override public void onBackPressed(){if(workspaceView!=null&&workspaceView.dismissKeyboard())return;if(!chatId.isEmpty()&&tab.equals("chats")){rememberDraft();chatId="";store.lastChat("");current=new JSONObject();renderPage();}else super.onBackPressed();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private TextView label(String text,float size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);return v;}
    private Button button(String title,boolean primary){return UiStyle.button(this,title,primary,false);}
    private EditText field(String hint,String value,boolean secret){EditText e=UiStyle.field(this,value);e.setHint(hint);e.setSingleLine(true);if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;}
    private GradientDrawable rounded(int color,int radius){return UiStyle.rounded(this,color,radius);}
    private void space(LinearLayout p,int height){p.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private static String number(long n){return String.format(Locale.US,"%,d",n);}
    private String date(long t){if(t>100000000000L)t/=1000;return new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(t*1000));}
    private static final class UsageChart extends View{
        final JSONArray days;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        UsageChart(Context c,JSONArray days){super(c);this.days=days;setContentDescription("最近30天每日Token柱状图");}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);int n=days==null?0:days.length();if(n==0)return;long max=1;for(int i=0;i<n;i++){JSONObject d=days.optJSONObject(i);if(d!=null)max=Math.max(max,d.optLong("tokens"));}float width=(float)getWidth()/n;for(int i=0;i<n;i++){JSONObject d=days.optJSONObject(i);float h=d==null?0:(float)d.optLong("tokens")/max*(getHeight()-10);paint.setColor(i==n-1?GREEN:0xff46675f);canvas.drawRoundRect(i*width+2,getHeight()-Math.max(4,h),(i+1)*width-2,getHeight(),3,3,paint);}}
    }
}
