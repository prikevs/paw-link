package com.pawlink.capture;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

final class ImuTimeline extends View {
    interface Seek {void seek(int videoMs,long imuNs);}
    interface Range {void changed(int start,int end);}
    private Range rangeListener;private int dragging;
    void rangeListener(Range r){rangeListener=r;}
    private final Paint paint=new Paint(3);private ImuData data;private VideoAlignment alignment;private int duration,position;private Seek seek;
    static final class Annotation {final int start,end;final String label;final boolean excluded,confirmed;Annotation(int s,int e,String l,boolean x,boolean c){start=s;end=e;label=l;excluded=x;confirmed=c;}}
    private List<Annotation> annotations=new ArrayList<>();private int rangeStart,rangeEnd;
    void annotations(List<Annotation> a){annotations=a;invalidate();}
    void range(int s,int e){rangeStart=s;rangeEnd=e;invalidate();}
    private double spanMs=30000,centerMs;private long imuCursorNs;
    ImuTimeline(Context c){super(c);setContentDescription("可缩放 IMU 活动时间轴，点击选择 IMU 时间");setBackgroundColor(CaptureUi.SOFT);}
    void load(ImuData d,VideoAlignment a,int ms,Seek cb){data=d;alignment=a;duration=ms;spanMs=ms;centerMs=ms/2.0;seek=cb;invalidate();}
    void position(int ms){position=ms;invalidate();}
    void cursor(long ns){imuCursorNs=ns;invalidate();}
    void viewport(int start,int end){spanMs=Math.min(duration,Math.max(500,end-start));centerMs=(start+end)/2.0;invalidate();}
    void zoom(double factor){spanMs=Math.max(500,Math.min(duration,spanMs*factor));centerMs=position;invalidate();}
    private double left(){return Math.max(0,Math.min(Math.max(0,duration-spanMs),centerMs-spanMs/2));}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();paint.setTextSize(CaptureUi.dp(getContext(),11));paint.setColor(CaptureUi.MUTED);paint.setStyle(Paint.Style.FILL);double left=left();c.drawText(String.format(java.util.Locale.US,"%.2f–%.2f 秒 · 黄:候选 绿:已标 红:排除",left/1000,(left+spanMs)/1000),8,16,paint);if(data==null)return;
        paint.setColor(0x44d79b24);for(ImuData.Candidate x:data.candidates){float a=(float)((alignment.video(x.startNs)-left)/spanMs*w),b=(float)((alignment.video(x.endNs)-left)/spanMs*w);if(b>=0&&a<=w)c.drawRect(Math.max(0,a),22,Math.min(w,b),h-15,paint);}
        paint.setColor(0x22356bba);float rs=(float)((rangeStart-left)/spanMs*w),re=(float)((rangeEnd-left)/spanMs*w);if(re>=0&&rs<=w)c.drawRect(Math.max(0,rs),22,Math.min(w,re),h-15,paint);
        for(Annotation a:annotations){float as=(float)((a.start-left)/spanMs*w),ae=(float)((a.end-left)/spanMs*w);if(ae<0||as>w)continue;paint.setColor(a.excluded?0xffb95b51:a.confirmed?CaptureUi.GREEN:CaptureUi.MUTED);c.drawRect(Math.max(0,as),h-14,Math.min(w,ae),h,paint);}
        Path accel=new Path(),gyro=new Path();boolean started=false;ImuData.Sample previous=null;double bx=0,by=0,bz=0;int lastPixel=-1;
        for(int si=data.lowerBound(alignment.host((long)left)-3_000_000_000L);si<data.samples.size();si++){ImuData.Sample s=data.samples.get(si);if(s.timeNs>alignment.host((long)(left+spanMs)))break;if(previous==null||!ImuData.continuous(previous,s)){bx=s.ax;by=s.ay;bz=s.az;started=false;if(previous!=null){paint.setColor(0x66c84e48);float gs=(float)((alignment.video(previous.timeNs)-left)/spanMs*w),ge=(float)((alignment.video(s.timeNs)-left)/spanMs*w);if(ge>=0&&gs<=w)c.drawRect(Math.max(0,gs),22,Math.min(w,Math.max(gs+2,ge)),h-15,paint);}}double dx=s.ax-bx,dy=s.ay-by,dz=s.az-bz;double value=Math.sqrt(dx*dx+dy*dy+dz*dz);bx+=.02*(s.ax-bx);by+=.02*(s.ay-by);bz+=.02*(s.az-bz);double ms=alignment.video(s.timeNs);if(ms>=left&&ms<=left+spanMs){float x=(float)((ms-left)/spanMs*w),y=(float)(h-15-Math.min(1,value/2)*(h-40)),g=(float)(h-15-Math.min(1,Math.sqrt(s.gx*s.gx+s.gy*s.gy+s.gz*s.gz)/250)*(h-40));if(!started){accel.moveTo(x,y);gyro.moveTo(x,g);started=true;}else if((int)x!=lastPixel||spanMs<5000){accel.lineTo(x,y);gyro.lineTo(x,g);}lastPixel=(int)x;}previous=s;}
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(CaptureUi.GREEN);c.drawPath(accel,paint);paint.setColor(0xff597ead);c.drawPath(gyro,paint);paint.setColor(0xff356bba);float hs=(float)((rangeStart-left)/spanMs*w),he=(float)((rangeEnd-left)/spanMs*w);c.drawLine(hs,20,hs,h-15,paint);c.drawLine(he,20,he,h-15,paint);c.drawCircle(hs,24,6,paint);c.drawCircle(he,24,6,paint);paint.setColor(CaptureUi.INK);float x=(float)((position-left)/spanMs*w);c.drawLine(x,20,x,h,paint);if(imuCursorNs>0){paint.setColor(0xffbd572c);x=(float)((alignment.video(imuCursorNs)-left)/spanMs*w);c.drawLine(x,20,x,h,paint);}paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!isEnabled())return false;if(alignment==null||getWidth()==0)return true;double left=left();int ms=(int)Math.round(left+Math.max(0,Math.min(getWidth(),e.getX()))/getWidth()*spanMs);ms=Math.max(0,Math.min(duration,ms));
        if(e.getAction()==MotionEvent.ACTION_DOWN){float start=(float)((rangeStart-left)/spanMs*getWidth()),end=(float)((rangeEnd-left)/spanMs*getWidth());float a=Math.abs(e.getX()-start),b=Math.abs(e.getX()-end);float hit=CaptureUi.dp(getContext(),18);dragging=Math.min(a,b)<=hit?(a<=b?1:2):0;getParent().requestDisallowInterceptTouchEvent(true);return true;}
        if(dragging!=0&&(e.getAction()==MotionEvent.ACTION_MOVE||e.getAction()==MotionEvent.ACTION_UP)){if(dragging==1)rangeStart=Math.max(0,Math.min(rangeEnd-1,ms));else rangeEnd=Math.min(duration,Math.max(rangeStart+1,ms));if(rangeListener!=null)rangeListener.changed(rangeStart,rangeEnd);invalidate();if(e.getAction()==MotionEvent.ACTION_UP){performClick();dragging=0;}return true;}
        if(e.getAction()==MotionEvent.ACTION_UP){performClick();long target=alignment.host(ms);ImuData.Sample best=null;long distance=Long.MAX_VALUE;int index=data.lowerBound(target);for(int i=Math.max(0,index-1);i<Math.min(data.samples.size(),index+1);i++){ImuData.Sample sample=data.samples.get(i);long d=Math.abs(sample.timeNs-target);if(d<distance){distance=d;best=sample;}}imuCursorNs=best==null?target:best.timeNs;if(seek!=null)seek.seek(ms,imuCursorNs);invalidate();}
        if(e.getAction()==MotionEvent.ACTION_CANCEL)dragging=0;return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
