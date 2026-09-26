package com.pawlink.capture;

import android.graphics.Color;
import android.graphics.Insets;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.ComponentActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ReviewActivity extends ComponentActivity {
    static final String EXTRA_SESSION_NAME = "session_name";
    private static final String[] LABELS = {
            "feed", "jump", "groom", "wash", "roll", "walk", "sleep", "unknown"
    };

    private static final class LabelEvent {
        long elapsedNs;
        final long wallMs;
        String label;
        String source;
        boolean reviewed;
        boolean excluded;
        String reason="";

        LabelEvent(long elapsedNs, long wallMs, String label, String source) {
            this.elapsedNs = elapsedNs;
            this.wallMs = wallMs;
            this.label = label;
            this.source = source;
        }
    }

    private final List<LabelEvent> events = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private File sessionDir;
    private VideoView videoView;
    private TextView segmentText;
    private Button playButton;
    private int currentIndex;
    private int durationMs;
    private long videoStartedElapsedNs;
    private boolean segmentPlaying;
    private ImuTimeline timeline;
    private boolean syncConfirmed;
    private boolean updatingQuality;
    private android.widget.CheckBox qualityBox;
    private org.json.JSONArray undo;
    private boolean exportRaw;
    private File latestRevision;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String sessionName = getIntent().getStringExtra(EXTRA_SESSION_NAME);
        File sessionsDir = new File(getExternalFilesDir(null), "sessions");
        sessionDir = sessionName == null ? null : new File(sessionsDir, sessionName);
        try {
            if (sessionDir == null || !sessionDir.getCanonicalFile().getParentFile()
                    .equals(sessionsDir.getCanonicalFile()) || !sessionDir.isDirectory()) {
                throw new IOException("无效的采集记录");
            }
            loadSession();
            buildUi();
        } catch (Exception error) {
            Toast.makeText(this, "无法打开记录：" + error.getMessage(),
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void loadSession() throws Exception {
        File manifestFile = new File(sessionDir, "manifest.json");
        JSONObject manifest = new JSONObject(readTextFile(manifestFile));
        videoStartedElapsedNs = manifest.optLong("video_started_elapsed_ns",
                manifest.getLong("started_elapsed_ns"));

        try (BufferedReader reader = new BufferedReader(
                new FileReader(new File(sessionDir, "labels.csv")))) {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length < 4) continue;
                events.add(new LabelEvent(Long.parseLong(parts[0]),
                        Long.parseLong(parts[1]), parts[2], parts[3]));
            }
        }
        File draft=new File(sessionDir,"review-v2.json");
        if(draft.exists()) {JSONObject j=new JSONObject(readTextFile(draft));syncConfirmed=j.optBoolean("sync_confirmed");videoStartedElapsedNs=j.optLong("video_reference_ns",videoStartedElapsedNs);restoreEvents(j.getJSONArray("segments"));}
        if (events.isEmpty()) {
            events.add(new LabelEvent(videoStartedElapsedNs,
                    System.currentTimeMillis(), "unknown", "system"));
        }
    }

    private void buildUi() {
        int padding = dp(14);
        LinearLayout root = CaptureUi.root(this);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(padding + bars.left, padding + bars.top,
                    padding + bars.right, padding + bars.bottom);
            return insets;
        });

        TextView title = text("视频复核 · 七类行为", 22, CaptureUi.INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        segmentText = text("正在加载…", 14, CaptureUi.MUTED);
        segmentText.setPadding(0, dp(6), 0, dp(6));
        root.addView(segmentText);

        videoView = new VideoView(this);
        android.widget.FrameLayout videoFrame=new android.widget.FrameLayout(this);
        videoFrame.setBackgroundColor(Color.BLACK);
        root.addView(videoFrame,new LinearLayout.LayoutParams(-1,0,1f));
        videoFrame.addView(videoView,new android.widget.FrameLayout.LayoutParams(-1,-1,android.view.Gravity.CENTER));
        MediaController controls = new MediaController(this);
        controls.setAnchorView(videoView);
        videoView.setMediaController(controls);
        videoView.setVideoPath(new File(sessionDir, "video.mp4").getAbsolutePath());
        videoView.setOnPreparedListener(this::onVideoPrepared);

        timeline=new ImuTimeline(this);root.addView(timeline,new LinearLayout.LayoutParams(-1,dp(66)));
        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        Button previous = button("上一段");
        previous.setOnClickListener(v -> showSegment(Math.max(0, currentIndex - 1), true));
        navigation.addView(previous, weighted());
        playButton = button("播放本段");
        playButton.setOnClickListener(v -> toggleSegmentPlayback());
        navigation.addView(playButton, weighted());
        Button keep = button("确认 / 下一段");
        keep.setOnClickListener(v -> keepAndNext());
        navigation.addView(keep, weighted());
        root.addView(navigation);

        android.widget.ScrollView toolsScroll=new android.widget.ScrollView(this);
        LinearLayout tools=CaptureUi.column(this);toolsScroll.addView(tools);
        root.addView(toolsScroll,new LinearLayout.LayoutParams(-1,dp(220)));
        for(int i=0;i<LABELS.length;i+=4){LinearLayout row=new LinearLayout(this);for(int j=i;j<Math.min(i+4,LABELS.length);j++){final String label=LABELS[j];Button choice=button(CaptureUi.name(label));choice.setTextSize(12);choice.setOnClickListener(v->applyReviewLabel(label));row.addView(choice,weighted());}tools.addView(row);}
        qualityBox=new android.widget.CheckBox(this);qualityBox.setText("排除本段：出画 / 遮挡 / 缺帧等");qualityBox.setTextColor(CaptureUi.INK);
        qualityBox.setOnCheckedChangeListener((b,checked)->{if(updatingQuality)return;checkpoint();LabelEvent e=events.get(currentIndex);e.excluded=checked;e.reason=checked?"unusable":"";persistDraft();updateSegmentText();});tools.addView(qualityBox);
        LinearLayout edits=new LinearLayout(this);Button split=button("此处分段"),boundary=button("调整边界"),undoButton=button("撤销");
        split.setOnClickListener(v->split());boundary.setOnClickListener(v->editBoundary());undoButton.setOnClickListener(v->{if(undo!=null){try{restoreEvents(undo);undo=null;persistDraft();showSegment(Math.min(currentIndex,events.size()-1),false);}catch(Exception e){toast(e.getMessage());}}});
        edits.addView(split,weighted());edits.addView(boundary,weighted());edits.addView(undoButton,weighted());tools.addView(edits);
        Button merge=button("与下一段合并");merge.setOnClickListener(v->{if(currentIndex+1>=events.size())return;checkpoint();events.remove(currentIndex+1);events.get(currentIndex).reviewed=false;persistDraft();showSegment(currentIndex,false);});tools.addView(merge);
        android.widget.CheckBox sync=new android.widget.CheckBox(this);sync.setText("已核对视频与 IMU 时间对齐（训练导出必需）");sync.setTextColor(CaptureUi.INK);sync.setChecked(syncConfirmed);sync.setOnCheckedChangeListener((b,c)->{syncConfirmed=c;persistDraft();});tools.addView(sync);
        Button offset=button("调整同步偏移");offset.setOnClickListener(v->{android.widget.EditText field=new android.widget.EditText(this);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);field.setHint("毫秒，正数把 IMU 向视频左侧移动");new android.app.AlertDialog.Builder(this).setTitle("校正同步（增量毫秒）").setView(field).setNegativeButton("取消",null).setPositiveButton("应用",(d,w)->{try{long delta=Long.parseLong(field.getText().toString())*1_000_000L;if(Math.abs(delta)>60_000_000_000L){toast("偏移不能超过 60 秒");return;}videoStartedElapsedNs+=delta;for(LabelEvent e:events){e.elapsedNs+=delta;e.reviewed=false;}syncConfirmed=false;sync.setChecked(false);undo=null;timeline.reference(videoStartedElapsedNs);persistDraft();showSegment(currentIndex,false);}catch(Exception e){toast("请输入整数毫秒");}}).show();});tools.addView(offset);
        TextView note=text("静止不等于睡眠。时间基于接收时刻，短动作请先核对同步。",12,CaptureUi.MUTED);tools.addView(note);
        Button save = button("保存复核结果");
        save.setOnClickListener(v -> saveReview());
        root.addView(save);
        LinearLayout exports=new LinearLayout(this);Button training=button("导出训练资料"),raw=button("导出原始资料");training.setOnClickListener(v->export(false));raw.setOnClickListener(v->export(true));exports.addView(training,weighted());exports.addView(raw,weighted());root.addView(exports);
        setContentView(root);
    }

    private void onVideoPrepared(MediaPlayer player) {
        durationMs = player.getDuration();
        try{timeline.load(new File(sessionDir,"imu.csv"),videoStartedElapsedNs,durationMs,ms->{stopSegmentPlayback();videoView.seekTo(ms);timeline.position(ms);});}catch(Exception e){toast("波形读取失败："+e.getMessage());}
        // Drop candidate transitions outside the playable video interval, preserving the last pre-roll state.
        while(events.size()>1 && events.get(1).elapsedNs<=videoStartedElapsedNs)events.remove(0);
        events.get(0).elapsedNs=videoStartedElapsedNs;
        events.removeIf(e->e.elapsedNs>=videoStartedElapsedNs+durationMs*1_000_000L);
        if(events.isEmpty())events.add(new LabelEvent(videoStartedElapsedNs,System.currentTimeMillis(),"unknown","system"));
        videoView.seekTo(1);
        showSegment(0, false);
    }

    private void showSegment(int index, boolean play) {
        if (events.isEmpty()) return;
        currentIndex = Math.max(0, Math.min(index, events.size() - 1));
        stopSegmentPlayback();
        videoView.seekTo(segmentStartMs(currentIndex));
        timeline.position(segmentStartMs(currentIndex));
        updateSegmentText();
        if (play) startSegmentPlayback();
    }

    private void updateSegmentText() {
        LabelEvent event = events.get(currentIndex);
        if(qualityBox!=null){updatingQuality=true;qualityBox.setChecked(event.excluded);updatingQuality=false;}
        long start = segmentStartMs(currentIndex);
        long end = segmentEndMs(currentIndex);
        int reviewedCount = 0;
        for (LabelEvent item : events) if (item.reviewed) reviewedCount++;
        segmentText.setText(String.format(Locale.US,
                "片段 %d/%d · %.1f–%.1f 秒\n行为：%s（%s） · 已确认 %d/%d",
                currentIndex + 1, events.size(), start / 1000f, end / 1000f,
                CaptureUi.name(event.label), event.reviewed?"人工确认":"候选", reviewedCount, events.size()));
        segmentText.append("\n"+evidenceAt(event.elapsedNs));
    }

    private int segmentStartMs(int index) {
        long value = (events.get(index).elapsedNs - videoStartedElapsedNs) / 1_000_000L;
        return (int) Math.max(0, Math.min(durationMs, value));
    }

    private int segmentEndMs(int index) {
        if (index + 1 < events.size()) return segmentStartMs(index + 1);
        return Math.max(segmentStartMs(index), durationMs);
    }

    private void toggleSegmentPlayback() {
        if (segmentPlaying) stopSegmentPlayback();
        else startSegmentPlayback();
    }

    private void startSegmentPlayback() {
        if (durationMs <= 0) return;
        int start = segmentStartMs(currentIndex);
        int end = segmentEndMs(currentIndex);
        if (videoView.getCurrentPosition() < start || videoView.getCurrentPosition() >= end) {
            videoView.seekTo(start);
        }
        segmentPlaying = true;
        playButton.setText("暂停");
        videoView.start();
        handler.post(segmentWatcher);
    }

    private final Runnable segmentWatcher = new Runnable() {
        @Override
        public void run() {
            if (!segmentPlaying) return;
            timeline.position(videoView.getCurrentPosition());
            if (videoView.getCurrentPosition() >= segmentEndMs(currentIndex) - 50) {
                videoView.seekTo(segmentStartMs(currentIndex));
                videoView.start();
                handler.postDelayed(this,100);
            } else {
                handler.postDelayed(this, 100);
            }
        }
    };

    private void stopSegmentPlayback() {
        segmentPlaying = false;
        handler.removeCallbacks(segmentWatcher);
        if (videoView != null && videoView.isPlaying()) videoView.pause();
        if (playButton != null) playButton.setText("播放本段");
    }

    private void keepAndNext() {
        LabelEvent e=events.get(currentIndex);
        if(!CaptureUi.trainable(e.label)&&!e.label.equals("unknown")){toast("请从七类行为或无法判断中选择，旧粗标签不能直接确认");return;}
        checkpoint();
        events.get(currentIndex).reviewed = true;
        events.get(currentIndex).source="human_review";
        persistDraft();
        advanceAfterReview();
    }

    private void applyReviewLabel(String label) {
        checkpoint();
        LabelEvent event = events.get(currentIndex);
        event.label = label;
        event.source = "human_review";
        event.reviewed = true;
        persistDraft();
        advanceAfterReview();
    }

    private void advanceAfterReview() {
        if (currentIndex + 1 < events.size()) showSegment(currentIndex + 1, true);
        else {
            stopSegmentPlayback();
            updateSegmentText();
            Toast.makeText(this, "已到最后一段，可以保存复核结果",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private String evidenceAt(long ns){
        String result="设备预测：此会话无记录";
        File file=new File(sessionDir,"evidence.csv");if(!file.exists())return result;
        try(BufferedReader r=new BufferedReader(new FileReader(file))){String line;long last=0;while((line=r.readLine())!=null){String[] a=line.split(",",4);if(a.length<4||!a[2].equals("prediction"))continue;long t=Long.parseLong(a[0]);if(t>ns+2_000_000_000L)break;if(t>=last){last=t;String payload=a[3].replace("\"","");String[] fields=payload.split(",");if(fields.length>=5)result="设备："+CaptureUi.name(fields[4])+" · 窗口 "+fields[3]+"（近似对齐）";}}if(last>0&&ns-last>5_000_000_000L)return "设备预测已过期";}catch(Exception e){return "设备证据无法读取";}return result;
    }
    private void flagImuGaps() throws Exception {
        boolean[] seen=new boolean[events.size()],bad=new boolean[events.size()];long previous=-1,sequence=-1;int previousIndex=-1,index=0;
        try(BufferedReader r=new BufferedReader(new FileReader(new File(sessionDir,"imu.csv")))){r.readLine();String line;while((line=r.readLine())!=null){String[] a=line.split(",");if(a.length<12)continue;long ns=Long.parseLong(a[1]),seq=Long.parseLong(a[3]);while(index+1<events.size()&&events.get(index+1).elapsedNs<=ns)index++;if(ns<videoStartedElapsedNs||ns>=videoStartedElapsedNs+durationMs*1_000_000L)continue;seen[index]=true;if(previous>=0&&(ns-previous>250_000_000L||((seq-sequence)&0xffffffffL)!=1)){for(int j=Math.max(0,previousIndex);j<=index;j++)bad[j]=true;}previous=ns;sequence=seq;previousIndex=index;}}
        for(int i=0;i<events.size();i++)if(!seen[i]||bad[i]){events.get(i).excluded=true;events.get(i).reason=!seen[i]?"no_imu":"imu_gap";}
    }

    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
    private org.json.JSONArray snapshot() throws Exception {
        org.json.JSONArray a=new org.json.JSONArray();for(LabelEvent e:events){JSONObject j=new JSONObject();j.put("elapsed_ns",e.elapsedNs);j.put("wall_ms",e.wallMs);j.put("label",e.label);j.put("source",e.source);j.put("reviewed",e.reviewed);j.put("excluded",e.excluded);j.put("reason",e.reason);a.put(j);}return a;
    }
    private void restoreEvents(org.json.JSONArray a) throws Exception {events.clear();for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);LabelEvent e=new LabelEvent(j.getLong("elapsed_ns"),j.getLong("wall_ms"),j.getString("label"),j.getString("source"));e.reviewed=j.optBoolean("reviewed");e.excluded=j.optBoolean("excluded");e.reason=j.optString("reason");events.add(e);}}
    private void checkpoint(){try{undo=snapshot();}catch(Exception e){toast("无法创建撤销点");}}
    private JSONObject reviewJson() throws Exception {JSONObject j=new JSONObject();j.put("schema_version",2);j.put("taxonomy_version","pawlink-actions-v1");j.put("reviewed_wall_ms",System.currentTimeMillis());j.put("sync_confirmed",syncConfirmed);j.put("sync_method","manual_check_receive_time");j.put("video_reference_ns",videoStartedElapsedNs);j.put("complete",events.stream().allMatch(e->e.reviewed||e.excluded));j.put("segments",snapshot());return j;}
    private boolean persistDraft(){try{File tmp=new File(sessionDir,"review-v2.json.tmp");try(BufferedWriter w=new BufferedWriter(new FileWriter(tmp))){w.write(reviewJson().toString(2));}if(!tmp.renameTo(new File(sessionDir,"review-v2.json")))throw new IOException("无法保存复核进度");return true;}catch(Exception e){toast("保存失败："+e.getMessage());return false;}}
    private void split(){int pos=videoView.getCurrentPosition();if(pos<=segmentStartMs(currentIndex)+50||pos>=segmentEndMs(currentIndex)-50){toast("请先暂停在片段内部，再分段");return;}checkpoint();LabelEvent old=events.get(currentIndex);old.reviewed=false;LabelEvent e=new LabelEvent(videoStartedElapsedNs+pos*1_000_000L,old.wallMs,old.label,old.source);e.excluded=old.excluded;e.reason=old.reason;events.add(currentIndex+1,e);persistDraft();showSegment(currentIndex+1,false);}
    private void editBoundary(){
        LinearLayout form=CaptureUi.column(this);android.widget.EditText input=new android.widget.EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setText(String.valueOf(segmentStartMs(currentIndex)));form.addView(text("本段起点（视频毫秒），首段固定为 0",14,CaptureUi.INK));form.addView(input);
        new android.app.AlertDialog.Builder(this).setTitle("调整片段起点").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{try{int ms=Integer.parseInt(input.getText().toString());if(currentIndex==0||ms<=segmentStartMs(currentIndex-1)||ms>=segmentEndMs(currentIndex)){toast("边界必须位于相邻片段内，首段起点不可改");return;}checkpoint();events.get(currentIndex).elapsedNs=videoStartedElapsedNs+ms*1_000_000L;events.get(currentIndex).reviewed=false;events.get(currentIndex-1).reviewed=false;persistDraft();showSegment(currentIndex,false);}catch(Exception e){toast("请输入有效毫秒数");}}).show();
    }
    private boolean saveFiles() {
        if(durationMs<=0){toast("视频尚未就绪");return false;}
        try {
            flagImuGaps();
            if(!persistDraft())return false;
            File revisions=new File(sessionDir,"reviews");latestRevision=new File(revisions,"revision-"+System.currentTimeMillis());if(!latestRevision.mkdirs())throw new IOException("无法创建复核版本");
            try(BufferedWriter w=new BufferedWriter(new FileWriter(new File(latestRevision,"review.json")))){w.write(reviewJson().toString(2));}
            try(BufferedWriter w=new BufferedWriter(new FileWriter(new File(latestRevision,"segments.csv")))){w.write("start_elapsed_ns,end_elapsed_ns,label,reviewed,excluded,reason\n");for(int i=0;i<events.size();i++){LabelEvent e=events.get(i);long end=i+1<events.size()?events.get(i+1).elapsedNs:videoStartedElapsedNs+durationMs*1_000_000L;w.write(e.elapsedNs+","+end+","+e.label+","+e.reviewed+","+e.excluded+","+e.reason+"\n");}}
            try(BufferedReader r=new BufferedReader(new FileReader(new File(sessionDir,"imu.csv")));BufferedWriter w=new BufferedWriter(new FileWriter(new File(latestRevision,"imu_training.csv")))){String line=r.readLine();if(line!=null)w.write(line+",session_name,review_version\n");int index=0;while((line=r.readLine())!=null){String[] a=line.split(",",-1);if(a.length<12)continue;long ns=Long.parseLong(a[1]);while(index+1<events.size()&&events.get(index+1).elapsedNs<=ns)index++;LabelEvent e=events.get(index);if(!syncConfirmed||!e.reviewed||e.excluded||!CaptureUi.trainable(e.label)||ns<videoStartedElapsedNs||ns>=videoStartedElapsedNs+durationMs*1_000_000L)continue;a[4]=e.label;a[5]="human_review";w.write(String.join(",",a)+","+sessionDir.getName()+","+latestRevision.getName()+"\n");}}
            return true;
        }catch(Exception e){toast("保存失败："+e.getMessage());return false;}
    }
    private void saveReview(){if(saveFiles())toast("复核版本已保存，原始文件未修改"+(!syncConfirmed?"；尚未核对同步，训练文件为空":""));}
    private void export(boolean raw){
        if(!raw&&!syncConfirmed){toast("请先核对视频与 IMU 同步，再启用训练导出");return;}
        if(!raw&&events.stream().noneMatch(e->e.reviewed&&!e.excluded&&CaptureUi.trainable(e.label))){toast("没有已确认的可训练片段");return;}
        if(!raw&&!saveFiles())return;exportRaw=raw;
        android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);intent.setType("application/zip");intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);intent.putExtra(android.content.Intent.EXTRA_TITLE,sessionDir.getName()+(raw?"-raw":"-training")+".zip");startActivityForResult(intent,41);
    }
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){super.onActivityResult(request,result,data);if(request!=41||result!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();final boolean raw=exportRaw;final File revision=latestRevision;
        new Thread(()->{try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(getContentResolver().openOutputStream(uri))){if(raw){for(File f:sessionDir.listFiles())if(f.isFile()&&!f.getName().startsWith("review")&&!f.getName().contains("reviewed"))zipFile(zip,f,f.getName());}else{for(File f:revision.listFiles())zipFile(zip,f,f.getName());zipFile(zip,new File(sessionDir,"manifest.json"),"source-manifest.json");}runOnUiThread(()->toast("导出完成"));}catch(Exception e){runOnUiThread(()->toast("导出失败："+e.getMessage()));}},"pawlink-export").start();
    }
    private void zipFile(java.util.zip.ZipOutputStream zip,File file,String name)throws IOException{zip.putNextEntry(new java.util.zip.ZipEntry(name));try(java.io.FileInputStream in=new java.io.FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)zip.write(b,0,n);}zip.closeEntry();}

    private String readTextFile(File file) throws IOException {
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                value.append(line).append('\n');
            }
        }
        return value.toString();
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value) {
        Button button = CaptureUi.button(this,value,false);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onPause(){super.onPause();stopSegmentPlayback();}

    @Override
    protected void onDestroy() {
        stopSegmentPlayback();
        if (videoView != null) videoView.stopPlayback();
        super.onDestroy();
    }
}
