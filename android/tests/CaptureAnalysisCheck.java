package com.pawlink.capture;
import java.util.*;
public final class CaptureAnalysisCheck {
    private static int checks;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    static ImuData.Sample sample(long sequence,long ms,int epoch){String[] f={"0",""+(ms*1_000_000),""+ms,""+sequence,"unknown","system","0","0","1","0","0","0",""+epoch};ImuData.Sample s=new ImuData.Sample(f,epoch);s.synchronizedTime=true;return s;}
    public static void main(String[] args){
        ClockSync c=new ClockSync();long offset=9_000_000_000L;
        for(int i=0;i<5;i++){long d=1000+i*100;check(c.observe(1,offset+d*1_000_000-4_000_000,d,d,offset+d*1_000_000+4_000_000),"accept roundtrip");}
        check(Math.abs(c.mapping(1).host(2000)-(offset+2_000_000_000L))<1000,"remove transmission delay");
        check(c.mapping(2)==null,"epoch isolation");check(!c.observe(1,0,100,90,20_000_000),"reject backwards device clock");check(!c.observe(1,0,0,0,300_000_000),"reject long roundtrip");
        ClockSync drift=new ClockSync();for(int i=0;i<5;i++){long ms=i*30000;long h=offset+Math.round(ms*1_000_000*1.0002);drift.observe(2,h-3_000_000,ms,ms,h+3_000_000);}check(Math.abs(drift.mapping(2).host(150000)-(offset+150030000000L))<1000,"fit clock drift");
        ImuData data=new ImuData();for(int i=0;i<50;i++)data.samples.add(sample(i,i*20,1));check(data.quality(0,1_000_000_000L).isEmpty(),"continuous coverage");check(data.quality(0,10_000_000_000L).equals("imu_edge_gap"),"catch trailing dropout");check(data.quality(-1_000_000_000L,1_000_000_000L).equals("imu_edge_gap"),"catch leading dropout");check(data.quality(2_000_000_000L,3_000_000_000L).equals("no_imu"),"empty range");data.samples.remove(20);check(data.quality(0,1_000_000_000L).equals("imu_gap"),"sequence gap");check(!ImuData.continuous(sample(1,100,1),sample(2,120,2)),"no cross epoch");check(ImuData.continuous(sample(0xffffffffL,100,1),sample(0,120,1)),"sequence wrap");
        List<ImuData.Window> feed=ImuData.windows(10_000_000_000L,30_000_000_000L,"feed",0,40_000_000_000L);check(feed.size()==19,"twenty-second annotation creates overlapping windows");check(feed.stream().allMatch(w->w.start>=10_000_000_000L&&w.end<=30_000_000_000L),"pure windows stay inside label");
        List<ImuData.Window> jump=ImuData.windows(12_300_000_000L,12_900_000_000L,"jump",0,20_000_000_000L);check(jump.size()==1&&jump.get(0).start<=12_300_000_000L&&jump.get(0).end>=12_900_000_000L&&jump.get(0).kind.equals("event_contains"),"preserve complete short event");check(ImuData.windows(0,600_000_000L,"jump",0,1_000_000_000L).isEmpty(),"reject insufficient context");
        List<ImuData.Window> roll=ImuData.windows(2_000_000_000L,6_000_000_000L,"roll",0,10_000_000_000L);check(roll.size()==1&&roll.get(0).end-roll.get(0).start>=4_000_000_000L,"long event preserved");
        VideoAlignment a=new VideoAlignment(0);a.anchors.add(new VideoAlignment.Anchor(0,offset));a.anchors.add(new VideoAlignment.Anchor(10000,offset+10_000_000_000L));a.fit();check(!a.valid,"two points not validated");a.anchors.add(new VideoAlignment.Anchor(20000,offset+20_000_000_000L));a.fit();check(a.valid&&a.host(5000)==offset+5_000_000_000L,"three-point video calibration");a.anchors.add(new VideoAlignment.Anchor(15000,offset+16_000_000_000L));a.fit();check(!a.valid,"reject inconsistent physical anchor");
        System.out.println("PASS "+checks+" analysis checks");
    }
}
