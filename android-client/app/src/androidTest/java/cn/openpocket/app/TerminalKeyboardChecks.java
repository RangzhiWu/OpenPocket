package cn.openpocket.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicReference;

final class TerminalKeyboardChecks {
    static void run(Instrumentation runner) throws Exception {
        Activity activity=runner.startActivitySync(new android.content.Intent(runner.getTargetContext(),MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<TerminalKeyboard> reference=new AtomicReference<>();AtomicReference<TextView> output=new AtomicReference<>();StringBuilder typed=new StringBuilder(),keys=new StringBuilder();
        runner.runOnMainSync(()->{
            // This isolated test surface contains no credentials or server data.
            activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            LinearLayout surface=new LinearLayout(activity);surface.setOrientation(LinearLayout.VERTICAL);surface.setBackgroundColor(MainActivity.BG);TextView display=new TextView(activity);display.setTextColor(MainActivity.TEXT);display.setTextSize(16);display.setPadding(24,48,24,24);display.setText("OpenPocket · 底部终端键盘\n\n$ 中文测试\n\n键盘展开抬高文本区，收起恢复\n中文只转换当前拼音，不学习历史\n");surface.addView(display,new LinearLayout.LayoutParams(-1,0,1));output.set(display);activity.setContentView(surface);
            TerminalKeyboard keyboard=new TerminalKeyboard(activity,new TerminalKeyboard.Host(){public void text(String text,boolean paste){typed.append(text);display.append(text);}public void key(String key){keys.append(key);}public void backspace(){typed.append("<BS>");}public void enter(){typed.append("<CR>");}});reference.set(keyboard);surface.addView(keyboard.view(),new LinearLayout.LayoutParams(-1,-2));
        });
        java.lang.reflect.Field ready=TerminalKeyboard.class.getDeclaredField("loaded");ready.setAccessible(true);long deadline=System.currentTimeMillis()+20000;while(!ready.getBoolean(null)&&System.currentTimeMillis()<deadline)Thread.sleep(100);if(!ready.getBoolean(null))throw new AssertionError("offline dictionary load failed or timed out");
        TerminalKeyboard keyboard=reference.get();View root=keyboard.view();int[] heights=new int[2];
        onMain(runner,()->{heights[0]=output.get().getHeight();if(keyboard.isShowing())throw new AssertionError("dock should start closed");keyboard.show();});
        runner.waitForIdleSync();Thread.sleep(250);
        onMain(runner,()->{
            heights[1]=output.get().getHeight();if(heights[0]-heights[1]!=root.getHeight()||root.getTop()<output.get().getBottom())throw new AssertionError("dock must reserve space without covering output");
            click(root,"EN / 中");for(String key:new String[]{"n","i","h","a","o"})click(root,key);click(root,"空格");
            if(!typed.toString().equals("你好"))throw new AssertionError("offline pinyin did not commit: "+typed);
            click(root,"中 / EN");click(root,"Ctrl");click(root,"c");click(root,"Alt");click(root,"x");click(root,"Ctrl+Z");click(root,"⌫");click(root,"↵");
            if(!keys.toString().equals("\u0003\u001bx\u001a"))throw new AssertionError("modifier/control key bytes");
            if(!typed.toString().equals("你好<BS><CR>"))throw new AssertionError("backspace/Enter callbacks");
            click(root,"EN / 中");for(String key:new String[]{"z","h","o","n","g","w","e","n"})click(root,key);click(root,"空格");
            if(!typed.toString().endsWith("中文"))throw new AssertionError("Chinese second phrase");
        });
        Thread.sleep(250);
        android.graphics.Bitmap screenshot=runner.getUiAutomation().takeScreenshot();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(runner.getTargetContext().getExternalFilesDir(null),"docked-keyboard.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}
        onMain(runner,()->click(root,"收起"));runner.waitForIdleSync();Thread.sleep(250);
        onMain(runner,()->{if(output.get().getHeight()!=heights[0])throw new AssertionError("hide must restore output height");keyboard.close();});
        workspaceChecks(runner,activity);
    }
    private static void workspaceChecks(Instrumentation runner,Activity activity)throws Exception{
        AtomicReference<WorkspaceView> workspace=new AtomicReference<>();
        onMain(runner,()->{
            WorkspaceView view=new WorkspaceView(activity,new Api("http://127.0.0.1:1",""),new WorkspaceView.State(),"",new WorkspaceView.Host(){public void run(MainActivity.Job job,MainActivity.Result result){throw new AssertionError("no live terminal calls allowed");}public void run(MainActivity.Job job,MainActivity.Result result,Runnable failed){throw new AssertionError("no live terminal calls allowed");}public void chooseTerminal(){}public void directoryChanged(){}public void upload(){}});
            workspace.set(view);activity.setContentView(view);
        });
        runner.waitForIdleSync();Thread.sleep(500);
        WorkspaceView view=workspace.get();TerminalCommandInput input=(TerminalCommandInput)member(view,"command");TerminalScreen screen=(TerminalScreen)member(view,"screen");TerminalKeyboard dock=(TerminalKeyboard)member(view,"keyboard");int[] fullHeight=new int[1];
        onMain(runner,()->{
            fullHeight[0]=screen.getHeight();
            if(input.onCheckIsTextEditor()||input.onCreateInputConnection(new android.view.inputmethod.EditorInfo())!=null||screen.onCheckIsTextEditor()||screen.onCreateInputConnection(new android.view.inputmethod.EditorInfo())!=null)throw new AssertionError("terminal inputs exposed a system IME connection");
            input.requestFocus();input.performClick();click(dock.view(),"h");click(dock.view(),"i");if(!input.getText().toString().equals("hi"))throw new AssertionError("draft focus lost on keyboard show");
        });
        runner.waitForIdleSync();Thread.sleep(300);
        onMain(runner,()->{
            if(!dock.isShowing()||screen.getHeight()>=fullHeight[0]||screen.getBottom()>input.getTop()+((View)input.getParent()).getTop())throw new AssertionError("workspace did not lift terminal");
            if(android.os.Build.VERSION.SDK_INT>=30&&view.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()))throw new AssertionError("system IME is visible");
            if(!view.dismissKeyboard())throw new AssertionError("Back must close dock");
        });
        runner.waitForIdleSync();Thread.sleep(250);
        onMain(runner,()->{if(screen.getHeight()!=fullHeight[0])throw new AssertionError("workspace output height not restored");if(view.dismissKeyboard())throw new AssertionError("hidden dock consumed Back");});
        touchScreen(runner,screen,true);runner.waitForIdleSync();
        onMain(runner,()->{if(dock.isShowing())throw new AssertionError("scroll unexpectedly opened keyboard");});
        touchScreen(runner,screen,false);runner.waitForIdleSync();Thread.sleep(300);
        onMain(runner,()->{if(!dock.isShowing()||input.hasFocus()||screen.getHeight()>=fullHeight[0])throw new AssertionError("terminal tap did not open keyboard and focus terminal");if(android.os.Build.VERSION.SDK_INT>=30&&view.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()))throw new AssertionError("terminal tap opened system IME");view.close();});
    }
    private static Object member(Object value,String name)throws Exception{java.lang.reflect.Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    private static void touchScreen(Instrumentation runner,View screen,boolean scroll){int[] point=new int[2];onMain(runner,()->{screen.getLocationOnScreen(point);point[0]+=screen.getWidth()/2;point[1]+=screen.getHeight()/2;});long now=android.os.SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,0,point[0],point[1],0);runner.sendPointerSync(down);down.recycle();if(scroll){android.view.MotionEvent move=android.view.MotionEvent.obtain(now,now+100,2,point[0],point[1]-100,0);runner.sendPointerSync(move);move.recycle();}android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+150,1,point[0],point[1]-(scroll?100:0),0);runner.sendPointerSync(up);up.recycle();}
    private static void onMain(Instrumentation runner,Runnable action){AtomicReference<Throwable> failure=new AtomicReference<>();runner.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new AssertionError("UI check failed",failure.get());}
    private static void click(View view,String label){View target=find(view,label);if(target==null)throw new AssertionError("key missing: "+label);target.performClick();}
    private static View find(View view,String label){if(view instanceof android.widget.Button&&((TextView)view).getText().toString().equals(label))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
}
