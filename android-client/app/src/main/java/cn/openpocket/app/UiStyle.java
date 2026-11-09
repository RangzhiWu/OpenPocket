package cn.openpocket.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.Button;
import android.widget.EditText;

/** Shared colors, rounded surfaces and controls for chat and workspace. */
final class UiStyle {
    private UiStyle() {}
    static int dp(Context context,float value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    static GradientDrawable rounded(Context context,int color,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(context,radius));return shape;}
    static RippleDrawable surface(Context context,int color,int radius,boolean border){GradientDrawable shape=rounded(context,color,radius);if(border)shape.setStroke(dp(context,1),MainActivity.CARD);return new RippleDrawable(ColorStateList.valueOf(0x228fe0c4),shape,null);}
    static Button button(Context context,String title,boolean primary,boolean filled){
        Button button=new Button(context);button.setText(title);button.setAllCaps(false);button.setTextSize(13);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(dp(context,12),0,dp(context,12),0);button.setStateListAnimator(null);button.setBackgroundTintList(null);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MainActivity.MUTED,primary?MainActivity.BG:MainActivity.TEXT}));
        button.setBackground(surface(context,primary?MainActivity.GREEN:filled?MainActivity.PANEL:Color.TRANSPARENT,12,false));return button;
    }
    static EditText field(Context context,String value){EditText field=new EditText(context);field.setText(value);field.setTextSize(15);field.setTextColor(MainActivity.TEXT);field.setHintTextColor(MainActivity.MUTED);field.setPadding(dp(context,14),dp(context,13),dp(context,14),dp(context,13));field.setBackgroundTintList(null);field.setBackground(rounded(context,MainActivity.PANEL,12));return field;}
}
