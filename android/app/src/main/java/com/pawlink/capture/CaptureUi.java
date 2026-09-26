package com.pawlink.capture;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.WindowInsets;
import android.widget.*;

final class CaptureUi {
    static final int BG=Color.rgb(245,245,239), INK=Color.rgb(24,44,37), MUTED=Color.rgb(89,105,95), GREEN=Color.rgb(35,100,70), SOFT=Color.rgb(226,236,223);
    static final String[] LABELS={"feed","jump","groom","wash","roll","walk","sleep","unknown"};
    static final String[] NAMES={"进食 / 饮水","跳跃","舔毛","洗脸","打滚","移动","睡觉","无法判断"};
    static String name(String v){for(int i=0;i<LABELS.length;i++)if(LABELS[i].equals(v))return NAMES[i];return "rest".equals(v)?"静止 / 休息":"locomotion".equals(v)?"移动候选":"collar_shake".equals(v)?"项圈异常晃动":v;}
    static boolean trainable(String v){for(int i=0;i<7;i++)if(LABELS[i].equals(v))return true;return false;}
    static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    static TextView text(Context c,String t,int size,int color){TextView v=new TextView(c);v.setText(t);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(c,4),0,dp(c,4));return v;}
    static Button button(Context c,String t,boolean primary){Button b=new Button(c);b.setText(t);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(primary?Color.WHITE:INK);b.setBackgroundTintList(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{Color.rgb(218,222,215),primary?GREEN:SOFT}));b.setElevation(0);b.setMinHeight(dp(c,48));return b;}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout root(Context c){LinearLayout r=column(c);r.setBackgroundColor(BG);r.setOnApplyWindowInsetsListener((v,w)->{Insets i=w.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(c,16)+i.left,i.top+dp(c,8),dp(c,16)+i.right,i.bottom+dp(c,8));return w;});return r;}
    static LinearLayout card(Context c){LinearLayout l=column(c);l.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));l.setBackground(bg(Color.WHITE,dp(c,18)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(c,8),0,dp(c,8));l.setLayoutParams(p);return l;}
    static TextView title(Context c,String s){TextView v=text(c,s,24,INK);v.setTypeface(null,Typeface.BOLD);return v;}
}
