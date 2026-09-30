package com.pawlink.capture;

import java.util.*;

/** Device millis -> host monotonic nanos; raw observations remain the authority. */
final class ClockSync {
    static final class Point {
        final int epoch; final long deviceNs, hostNs, uncertaintyNs;
        Point(int e,long d,long h,long u){epoch=e;deviceNs=d;hostNs=h;uncertaintyNs=u;}
    }
    static final class Mapping {
        final long deviceOrigin,hostOrigin,uncertaintyNs,firstDeviceNs,lastDeviceNs; final double slope;
        Mapping(long d,long h,double s,long u,long first,long last){deviceOrigin=d;hostOrigin=h;slope=s;uncertaintyNs=u;firstDeviceNs=first;lastDeviceNs=last;}
        long uncertaintyAt(long deviceMs){long ns=deviceMs*1_000_000L;long outside=Math.max(0,Math.max(firstDeviceNs-ns,ns-lastDeviceNs));return uncertaintyNs+Math.round(outside*.002);}
        long host(long deviceMs){return hostOrigin+Math.round((deviceMs*1_000_000L-deviceOrigin)*slope);}
    }
    private final List<Point> points=new ArrayList<>();
    synchronized boolean observe(int epoch,long t1,long t2Ms,long t3Ms,long t4) {
        long processing=((t3Ms-t2Ms)&0xffffffffL)*1_000_000L;
        long rtt=t4-t1-processing;
        if(rtt<0||rtt>200_000_000L)return false;
        points.add(new Point(epoch,t2Ms*1_000_000L+processing/2,t1+(t4-t1)/2,rtt/2+1_000_000L));
        return true;
    }
    synchronized Mapping mapping(int epoch){
        List<Point> p=new ArrayList<>();for(Point x:points)if(x.epoch==epoch)p.add(x);
        if(p.size()<3)return null;
        p.sort(Comparator.comparingLong(x->x.uncertaintyNs));
        Point anchor=p.get(0);double slope=1;long spread=0;
        // A single burst estimates offset; drift is fitted only over longer spans.
        double xy=0,xx=0;for(Point x:p){long delta=x.deviceNs-anchor.deviceNs;spread=Math.max(spread,Math.abs(delta));if(x.uncertaintyNs>anchor.uncertaintyNs+10_000_000L)continue;double w=1.0/Math.max(1,x.uncertaintyNs);xx+=w*delta*delta;xy+=w*delta*(x.hostNs-anchor.hostNs);}
        if(spread>=30_000_000_000L&&xx>0){double fitted=xy/xx;if(fitted>=.998&&fitted<=1.002)slope=fitted;else return null;}
        long residual=0;for(Point x:p)if(x.uncertaintyNs<=anchor.uncertaintyNs+10_000_000L)residual=Math.max(residual,Math.abs(x.hostNs-(anchor.hostNs+Math.round((x.deviceNs-anchor.deviceNs)*slope))));
        long first=Long.MAX_VALUE,last=Long.MIN_VALUE;for(Point x:p){first=Math.min(first,x.deviceNs);last=Math.max(last,x.deviceNs);}
        return new Mapping(anchor.deviceNs,anchor.hostNs,slope,anchor.uncertaintyNs+residual,first,last);
    }
    synchronized void clear(){points.clear();}
}
