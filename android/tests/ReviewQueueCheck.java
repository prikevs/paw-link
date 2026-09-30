package com.pawlink.capture;
import java.util.*;
public final class ReviewQueueCheck {
 static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);}
 public static void main(String[] args){
  ImuData d=new ImuData();d.candidates.add(new ImuData.Candidate(12_000_000_000L,13_000_000_000L,"活动"));d.candidates.add(new ImuData.Candidate(16_000_000_000L,17_000_000_000L,"活动"));VideoAlignment a=new VideoAlignment(10_000_000_000L);List<ReviewQueue.Item> q=ReviewQueue.build(d,a,8000);
  check(q.size()==2,"chronological queue");check(q.get(0).startMs==2000&&q.get(0).endMs==3000,"keep inferred action boundaries");check(q.get(0).previewStart()==0&&q.get(0).previewEnd(8000)==5000,"two seconds context is separate");check(q.get(1).previewEnd(8000)==8000,"clip context to video");Set<String> done=new HashSet<>();check(ReviewQueue.firstPending(q,done,-1)==0,"start at first pending");done.add(q.get(0).id);check(ReviewQueue.firstPending(q,done,0)==1,"advance after confirm");done.add(q.get(1).id);check(ReviewQueue.firstPending(q,done,1)==-1,"completion");done.remove(q.get(0).id);check(ReviewQueue.firstPending(q,done,1)==0,"return to pending earlier segment");a.referenceNs+=100_000_000L;List<ReviewQueue.Item> shifted=ReviewQueue.build(d,a,8000);check(shifted.get(0).id.equals(q.get(0).id),"candidate identity survives video calibration");check(shifted.get(0).startMs==1900,"use calibrated video alignment");System.out.println("PASS: candidate boundaries, video context, queue progression, stable identity and calibration");
 }
}
