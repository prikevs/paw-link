package com.pawlink.capture;
import java.util.*;

/** Physical video/IMU correspondences; fit and residual are saved, not inferred from Start. */
final class VideoAlignment {
    static final class Anchor {final long videoMs,hostNs;Anchor(long v,long h){videoMs=v;hostNs=h;}}
    final List<Anchor> anchors=new ArrayList<>();long referenceNs;double nanosPerMs=1_000_000;long residualNs;long annotationErrorNs=50_000_000L;boolean valid;
    VideoAlignment(long approximate){referenceNs=approximate;}
    void fit(){valid=false;if(anchors.size()<3)return;double meanV=0,meanH=0;long origin=anchors.get(0).hostNs;long min=Long.MAX_VALUE,max=Long.MIN_VALUE;for(Anchor a:anchors){meanV+=a.videoMs;meanH+=a.hostNs-origin;min=Math.min(min,a.videoMs);max=Math.max(max,a.videoMs);}if(max-min<10000)return;meanV/=anchors.size();meanH/=anchors.size();double vv=0,vh=0;for(Anchor a:anchors){double v=a.videoMs-meanV;vv+=v*v;vh+=v*(a.hostNs-origin-meanH);}if(vv==0)return;double slope=vh/vv;if(slope<980000||slope>1020000)return;nanosPerMs=slope;referenceNs=origin+Math.round(meanH-slope*meanV);residualNs=0;for(Anchor a:anchors)residualNs=Math.max(residualNs,Math.abs(a.hostNs-host(a.videoMs)));valid=residualNs<=100_000_000L;}
    boolean covered(long startMs,long endMs){long first=Long.MAX_VALUE,last=Long.MIN_VALUE;for(Anchor a:anchors){first=Math.min(first,a.videoMs);last=Math.max(last,a.videoMs);}return valid&&startMs>=first&&endMs<=last;}
    long host(long videoMs){return referenceNs+Math.round(videoMs*nanosPerMs);}
    int video(long hostNs){return (int)Math.round((hostNs-referenceNs)/nanosPerMs);}
    long uncertainty(long clockNs){return residualNs+annotationErrorNs+clockNs;}
}
