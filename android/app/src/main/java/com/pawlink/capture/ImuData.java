package com.pawlink.capture;

import java.io.*;
import java.util.*;

/** Pure Java analysis shared by review, candidate generation and export. */
final class ImuData {
    static final class Sample {
        final String[] fields;final long receivedNs,deviceMs,sequence;final int epoch;
        long timeNs; boolean synchronizedTime;
        final double ax,ay,az,gx,gy,gz;
        Sample(String[] f,int e){fields=f;receivedNs=Long.parseLong(f[1]);deviceMs=Long.parseLong(f[2]);sequence=Long.parseLong(f[3]);epoch=e;timeNs=receivedNs;ax=Double.parseDouble(f[6]);ay=Double.parseDouble(f[7]);az=Double.parseDouble(f[8]);gx=Double.parseDouble(f[9]);gy=Double.parseDouble(f[10]);gz=Double.parseDouble(f[11]);}
    }
    static final class Candidate {
        final long startNs,endNs;final String kind;
        Candidate(long s,long e,String k){startNs=s;endNs=e;kind=k;}
    }
    final List<Sample> samples=new ArrayList<>();final List<Candidate> candidates=new ArrayList<>();
    String header;long syncUncertaintyNs;boolean allMapped=true;
    static ImuData load(File dir)throws IOException {
        ImuData result=new ImuData();ClockSync clock=new ClockSync();Map<Integer,ClockSync.Mapping> mappings=new HashMap<>();File sync=new File(dir,"sync.csv");
        if(sync.isFile())try(BufferedReader r=new BufferedReader(new FileReader(sync))){r.readLine();String line;while((line=r.readLine())!=null){String[] a=line.split(",");if(a.length>=5)clock.observe(Integer.parseInt(a[0]),Long.parseLong(a[1]),Long.parseLong(a[2]),Long.parseLong(a[3]),Long.parseLong(a[4]));}}
        try(BufferedReader r=new BufferedReader(new FileReader(new File(dir,"imu.csv")))){result.header=r.readLine();String line;while((line=r.readLine())!=null){String[] f=line.split(",",-1);if(f.length<12)continue;Sample s=new Sample(f,f.length>12?Integer.parseInt(f[12]):0);if(!mappings.containsKey(s.epoch))mappings.put(s.epoch,clock.mapping(s.epoch));ClockSync.Mapping m=mappings.get(s.epoch);if(m!=null){s.timeNs=m.host(s.deviceMs);s.synchronizedTime=true;result.syncUncertaintyNs=Math.max(result.syncUncertaintyNs,m.uncertaintyAt(s.deviceMs));}else result.allMapped=false;result.samples.add(s);}}
        result.samples.sort(Comparator.comparingLong(s->s.timeNs));
        result.findCandidates();return result;
    }
    static boolean continuous(Sample a,Sample b){return a.epoch==b.epoch&&((b.sequence-a.sequence)&0xffffffffL)==1&&b.timeNs>a.timeNs&&b.timeNs-a.timeNs<=100_000_000L&&((b.deviceMs-a.deviceMs)&0xffffffffL)<=100;}
    int lowerBound(long ns){int lo=0,hi=samples.size();while(lo<hi){int mid=(lo+hi)>>>1;if(samples.get(mid).timeNs<ns)lo=mid+1;else hi=mid;}return lo;}
    String quality(long start,long end) {
        Sample first=null,last=null;for(int i=lowerBound(start);i<samples.size();i++){Sample s=samples.get(i);if(s.timeNs>=end)break;if(last!=null&&!continuous(last,s))return "imu_gap";if(first==null)first=s;last=s;}
        if(first==null)return "no_imu";
        if(first.timeNs-start>60_000_000L||end-last.timeNs>60_000_000L)return "imu_edge_gap";
        return "";
    }
    private void findCandidates(){
        if(samples.isEmpty())return;double bx=samples.get(0).ax,by=samples.get(0).ay,bz=samples.get(0).az;long start=-1,lastActive=0;double peak=0;Sample previous=null;
        for(Sample s:samples){if(previous!=null&&!continuous(previous,s)){if(start>=0)addCandidate(start,lastActive,peak);start=-1;peak=0;bx=s.ax;by=s.ay;bz=s.az;}
            double dx=s.ax-bx,dy=s.ay-by,dz=s.az-bz;double dynamic=Math.sqrt(dx*dx+dy*dy+dz*dz),gyro=Math.sqrt(s.gx*s.gx+s.gy*s.gy+s.gz*s.gz);
            boolean active=dynamic>.10||gyro>25;bx+=.02*(s.ax-bx);by+=.02*(s.ay-by);bz+=.02*(s.az-bz);
            if(active){if(start<0)start=s.timeNs;lastActive=s.timeNs;peak=Math.max(peak,dynamic);}
            else if(start>=0&&s.timeNs-lastActive>400_000_000L){addCandidate(start,lastActive,peak);start=-1;peak=0;}
            previous=s;
        }if(start>=0)addCandidate(start,lastActive,peak);
    }
    private void addCandidate(long s,long e,double peak){if(e-s>=80_000_000L||peak>.8)candidates.add(new Candidate(s,e+20_000_000L,e-s<1_000_000_000L?"短事件候选":"活动候选"));}
    static final class Window {final long start,end;final String label,kind;Window(long s,long e,String l,String k){start=s;end=e;label=l;kind=k;}}
    static List<Window> windows(long start,long end,String label,long videoStart,long videoEnd){
        List<Window> result=new ArrayList<>();long length=2_000_000_000L;
        if(label.equals("jump")||label.equals("roll")){
            // Event label means contains the complete event, not majority sample label.
            length=Math.max(length,end-start+400_000_000L);
            long s=Math.max(videoStart,Math.min(start-(length-(end-start))/2,videoEnd-length));
            if(s<=start&&s+length>=end&&s+length<=videoEnd)result.add(new Window(s,s+length,label,"event_contains"));
        }else for(long s=start;s+length<=end;s+=1_000_000_000L)result.add(new Window(s,s+length,label,"pure_behavior"));
        return result;
    }
}
