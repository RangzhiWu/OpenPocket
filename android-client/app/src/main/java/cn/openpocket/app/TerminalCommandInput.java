package cn.openpocket.app;

import android.content.Context;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

/** Selectable command draft edited by our keyboard, with no system IME connection. */
final class TerminalCommandInput extends EditText {
    TerminalCommandInput(Context context,String value){
        super(context);
        setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setShowSoftInputOnFocus(false);
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        setText(value);setTextColor(MainActivity.TEXT);setHintTextColor(MainActivity.MUTED);
        setBackgroundTintList(null);setBackground(UiStyle.rounded(context,MainActivity.PANEL,12));
    }
    @Override public boolean onCheckIsTextEditor(){return false;}
    @Override public InputConnection onCreateInputConnection(EditorInfo attributes){return null;}
}
