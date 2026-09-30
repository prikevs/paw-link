package com.pawlink.capture;

import java.util.*;

/** Separate inferred action boundaries from video context; padding is never a label. */
final class ReviewQueue {
    static final int DEFAULT_MARGIN_MS=2000;
    static final class Item {
        final String id,kind;final long imuStartNs,imuEndNs;final int startMs,endMs;
        Item(String id,String kind,long s,long e,int start,int end){this.id=id;this.kind=kind;imuStartNs=s;imuEndNs=e;startMs=start;endMs=end;}
        int previewStart(){return Math.max(0,startMs-DEFAULT_MARGIN_MS);}
        int previewEnd(int duration){return (int)Math.min(duration,(long)endMs+DEFAULT_MARGIN_MS);}
    }
    static List<Item> build(ImuData data,VideoAlignment alignment,int duration){
        List<Item> result=new ArrayList<>();
        for(ImuData.Candidate c:data.candidates){int start=Math.max(0,alignment.video(c.startNs)),end=Math.min(duration,alignment.video(c.endNs));if(end<=start)continue;result.add(new Item(c.startNs+":"+c.endNs,c.kind,c.startNs,c.endNs,start,end));}
        return result;
    }
    static int firstPending(List<Item> items,Set<String> handled,int after){for(int i=after+1;i<items.size();i++)if(!handled.contains(items.get(i).id))return i;for(int i=0;i<=after&&i<items.size();i++)if(!handled.contains(items.get(i).id))return i;return -1;}
}
