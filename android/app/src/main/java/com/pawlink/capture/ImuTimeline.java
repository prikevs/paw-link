package com.pawlink.capture;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.io.*;
import java.util.*;
final class ImuTimeline extends View {
    interface Seek {void seek(int ms);}
    private final ArrayList<double[]> samples=new ArrayList<>();
    private final Paint paint=new Paint(3);private long origin;private int duration,position;private Seek seek;
    ImuTimeline(Context c){super(c);setContentDescription("加速度合量波形，点击定位视频");setBackgroundColor(CaptureUi.SOFT);}
    void load(File f,long ns,int ms,Seek cb)throws IOException{origin=ns;duration=ms;seek=cb;samples.clear();try(BufferedReader r=new BufferedReader(new FileReader(f))){r.readLine();String l;int n=0;while((l=r.readLine())!=null){if(n++%5!=0)continue;String[] a=l.split(",");if(a.length<12)continue;double x=Double.parseDouble(a[6]),y=Double.parseDouble(a[7]),z=Double.parseDouble(a[8]);samples.add(new double[]{Long.parseLong(a[1]),Math.sqrt(x*x+y*y+z*z)});}}invalidate();}
    void position(int ms){position=ms;invalidate();}
    void reference(long ns){origin=ns;invalidate();}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();paint.setColor(CaptureUi.MUTED);paint.setTextSize(CaptureUi.dp(getContext(),11));c.drawText("加速度合量 (g) · 点击定位",8,16,paint);if(samples.isEmpty()){c.drawText("无 IMU 数据",8,h-12,paint);return;}double max=1;for(double[] s:samples)max=Math.max(max,s[1]);Path p=new Path();boolean started=false;for(double[] s:samples){double ms=(s[0]-origin)/1e6;if(ms<0||ms>duration)continue;float x=(float)(ms/Math.max(1,duration)*w),y=(float)(h-12-s[1]/max*(h-36));if(!started){p.moveTo(x,y);started=true;}else p.lineTo(x,y);}paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(CaptureUi.GREEN);c.drawPath(p,paint);paint.setColor(CaptureUi.INK);float x=(float)position/Math.max(1,duration)*w;c.drawLine(x,20,x,h,paint);paint.setStyle(Paint.Style.FILL);}
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_UP){performClick();if(seek!=null)seek.seek((int)(Math.max(0,Math.min(getWidth(),e.getX()))/getWidth()*duration));return true;}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
