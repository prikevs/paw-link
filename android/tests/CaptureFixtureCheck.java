package com.pawlink.capture;
import java.io.*;
import java.nio.file.*;
public final class CaptureFixtureCheck {
    public static void main(String[] args)throws Exception{
        File dir=Files.createTempDirectory("pawlink-fixture-").toFile();
        try{
            try(PrintWriter s=new PrintWriter(new File(dir,"sync.csv"))){s.println("epoch,t1_host_ns,t2_device_ms,t3_device_ms,t4_host_ns");for(int i=0;i<5;i++){long ms=1000+i*100;long host=10_000_000_000L+ms*1_000_000L;s.println("1,"+(host-3_000_000)+","+ms+","+ms+","+(host+3_000_000));}}
            try(PrintWriter f=new PrintWriter(new File(dir,"imu.csv"))){f.println("host_time_ns,elapsed_time_ns,device_time_ms,sequence,label,label_source,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,epoch");for(int i=0;i<500;i++){int ms=1000+i*20;double x=i>=100&&i<130?Math.sin(i)*.9:0;double gyro=i>=100&&i<130?80:0;f.println("0,"+(10_000_000_000L+ms*1_000_000L+30_000_000L)+","+ms+","+i+",unknown,system,"+x+",0,1,0,"+gyro+",0,1");}}
            ImuData d=ImuData.load(dir);CaptureAnalysisCheck.check(d.allMapped,"mapped fixture");CaptureAnalysisCheck.check(d.samples.get(0).timeNs==11_000_000_000L,"use sampling clock not reception");CaptureAnalysisCheck.check(!d.candidates.isEmpty(),"activity candidate without cat detector");CaptureAnalysisCheck.check(d.quality(11_000_000_000L,21_000_000_000L).isEmpty(),"full recording coverage");
            // Simulate 30 minutes / 90,000 samples; binary interval selection must be bounded.
            ImuData longRecording=new ImuData();for(int i=0;i<90000;i++)longRecording.samples.add(CaptureAnalysisCheck.sample(i,i*20,1));long begin=System.nanoTime();for(int i=0;i<1700;i++)CaptureAnalysisCheck.check(longRecording.quality(i*1_000_000_000L,i*1_000_000_000L+2_000_000_000L).isEmpty(),"long session window");long elapsed=(System.nanoTime()-begin)/1_000_000;
            try(PrintWriter f=new PrintWriter(new File(dir,"imu.csv"))){f.println("host_time_ns,elapsed_time_ns,device_time_ms,sequence,label,label_source,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,epoch");for(int i=0;i<90000;i++){long ms=1000+i*20;f.println("0,"+(10_000_000_000L+ms*1_000_000L+30_000_000L)+","+ms+","+i+",unknown,system,0,0,1,0,0,0,1");}}
            long loadStart=System.nanoTime();ImuData loaded=ImuData.load(dir);long loadMs=(System.nanoTime()-loadStart)/1_000_000;CaptureAnalysisCheck.check(loaded.samples.size()==90000&&loadMs<5000,"long session file loading");CaptureAnalysisCheck.check(elapsed<5000,"long-session range lookup");System.out.println("PASS fixture: mapped candidate + 30-minute interval checks in "+elapsed+" ms; file load "+loadMs+" ms");
        }finally{for(File f:dir.listFiles())f.delete();dir.delete();}
    }
}
