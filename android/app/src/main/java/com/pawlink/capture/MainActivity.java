package com.pawlink.capture;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity implements BleClient.Listener {
    private static final int PERMISSIONS_REQUEST = 20;
    private static final String[] LABELS = {
            "rest", "locomotion", "feed", "groom", "collar_shake", "unknown"
    };

    private PreviewView previewView;
    private DetectionOverlay detectionOverlay;
    private TextView statusText;
    private TextView metricsText;
    private TextView activeLabelText;
    private TextView visionText;
    private Button connectButton;
    private Button recordButton;
    private BleClient bleClient;
    private VisionAnalyzer visionAnalyzer;
    private ExecutorService cameraExecutor;
    private final ExecutorService fileExecutor=Executors.newSingleThreadExecutor();
    private Recorder cameraRecorder;
    private Recording recording;
    private volatile SessionRecorder session;
    private volatile File savingDirectory;
    private File exportingDirectory;
    private LinearLayout capturePage, recordsPage, devicePage;
    private TextView deviceText, predictionText, timerText;
    private android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile long lastFrameNs;
    private long recordingStartNs, batteryNs;
    private long lastDeviceMs=-1;
    private boolean multipleCats;
    private boolean cameraReady, finalizing;
    private volatile boolean handTest;
    private android.widget.CheckBox testMode;
    private android.widget.EditText catInput,mountInput;
    private final ClockSync clock=new ClockSync();
    private String battery="未知", voltage="未知", power="未知", prediction="尚未收到预测";
    private int selectedPage;
    private final java.util.Set<String> deletingSessions = new java.util.HashSet<>();
    private long received;
    private long firstFrameNs;
    private long lastUiUpdateNs;
    private volatile boolean bleConnected;
    private volatile String lastVisionSuggestion = "unknown";
    private volatile boolean manualOverrideActive;
    private volatile String lastAutoLabel = "unknown";
    private volatile long lastCatSeenNs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();
        bleClient = new BleClient(this, this);
        buildUi();
        requestRequiredPermissions();
    }

    private void buildUi() {
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=CaptureUi.root(this);
        root.addView(CaptureUi.title(this,"PawLink Capture"));
        statusText=text("准备相机与项圈…",14,CaptureUi.MUTED); root.addView(statusText);
        FrameLayout pages=new FrameLayout(this);root.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        capturePage=CaptureUi.column(this);pages.addView(capturePage,new FrameLayout.LayoutParams(-1,-1));
        capturePage.addView(text("同步采集 · 结束后人工确认",16,CaptureUi.INK));
        testMode=new android.widget.CheckBox(this);testMode.setText("手持测试（关闭猫检测，不进入猫训练集）");capturePage.addView(testMode);
        testMode.setOnCheckedChangeListener((b,c)->{handTest=c;detectionOverlay.clear();activeLabelText.setText(c?"手持测试 · IMU 候选在复核时生成":"视觉候选：待复核");});
        catInput=new android.widget.EditText(this);catInput.setSingleLine();catInput.setHint("猫 ID（测试可留空）");capturePage.addView(catInput);
        mountInput=new android.widget.EditText(this);mountInput.setSingleLine();mountInput.setHint("佩戴方向与松紧 / 手持方式");capturePage.addView(mountInput);
        timerText=text("00:00 · 720p 视频 + 六轴 IMU",14,CaptureUi.MUTED);capturePage.addView(timerText);
        FrameLayout cameraFrame=new FrameLayout(this);capturePage.addView(cameraFrame,new LinearLayout.LayoutParams(-1,0,1));
        previewView=new PreviewView(this);previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        cameraFrame.addView(previewView,new FrameLayout.LayoutParams(-1,-1));
        detectionOverlay=new DetectionOverlay(this);cameraFrame.addView(detectionOverlay,new FrameLayout.LayoutParams(-1,-1));
        activeLabelText=text("视觉候选：unknown · 待复核",14,Color.WHITE);
        activeLabelText.setPadding(dp(8),dp(6),dp(8),dp(6));activeLabelText.setBackgroundColor(0xcc182c25);
        FrameLayout.LayoutParams overlay=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START);overlay.setMargins(dp(8),0,dp(8),dp(8));cameraFrame.addView(activeLabelText,overlay);
        LinearLayout info=CaptureUi.card(this);capturePage.addView(info);
        metricsText=text("IMU：尚未连接",16,CaptureUi.INK);info.addView(metricsText);
        visionText=text("视觉：正在初始化…",13,CaptureUi.MUTED);info.addView(visionText);
        predictionText=text("设备：尚未收到预测",13,CaptureUi.MUTED);info.addView(predictionText);
        Button bookmark=button("＋ 标记此刻");bookmark.setOnClickListener(v->{if(session==null){Toast.makeText(this,"请先开始采集",Toast.LENGTH_SHORT).show();return;}session.recordEvidence("bookmark","manual",SystemClock.elapsedRealtimeNanos());Toast.makeText(this,"已保存书签，自动候选继续记录",Toast.LENGTH_SHORT).show();});capturePage.addView(bookmark);
        recordButton=CaptureUi.button(this,"开始采集",true);recordButton.setEnabled(false);recordButton.setOnClickListener(v->toggleRecording());capturePage.addView(recordButton);
        android.widget.ScrollView recordsScroll=new android.widget.ScrollView(this);recordsPage=CaptureUi.column(this);recordsScroll.addView(recordsPage);pages.addView(recordsScroll,new FrameLayout.LayoutParams(-1,-1));recordsScroll.setTag("records");
        android.widget.ScrollView deviceScroll=new android.widget.ScrollView(this);devicePage=CaptureUi.column(this);deviceScroll.addView(devicePage);pages.addView(deviceScroll,new FrameLayout.LayoutParams(-1,-1));deviceScroll.setTag("device");
        devicePage.addView(CaptureUi.title(this,"当前项圈"));deviceText=text("未连接",16,CaptureUi.INK);devicePage.addView(deviceText);
        connectButton=CaptureUi.button(this,"连接项圈",true);connectButton.setOnClickListener(v->{if(!bleClient.hasPermission())requestRequiredPermissions();else bleClient.scanAndConnect();});devicePage.addView(connectButton);Button resync=button("重新测量时钟同步");resync.setOnClickListener(v->bleClient.synchronizeNow());devicePage.addView(resync);
        LinearLayout mount=CaptureUi.card(this);mount.addView(text("佩戴方向",18,CaptureUi.INK));mount.addView(text("USB-C 朝左 · 元件面朝外\n板子长轴沿项圈方向",15,CaptureUi.MUTED));devicePage.addView(mount);
        LinearLayout abilities=CaptureUi.card(this);abilities.addView(text("模型能力与标注",18,CaptureUi.INK));abilities.addView(text("设备提供休息、移动、进食与舔毛预测。\n跳跃、洗脸、打滚通过视频人工标注。\n静止不等于睡眠；预测不是训练真值。",14,CaptureUi.MUTED));devicePage.addView(abilities);
        Button off=button("设备关机");off.setOnClickListener(v->{if(recording!=null||finalizing){Toast.makeText(this,"请先结束采集并保存",Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle("关闭项圈？").setMessage("下次使用需要按 Reset 重新启动。").setNegativeButton("取消",null).setPositiveButton("确认关机",(d,w)->bleClient.shutdown()).show();});devicePage.addView(off);
        LinearLayout nav=new LinearLayout(this);String[] names={"采集","记录","设备"};
        for(int i=0;i<3;i++){final int n=i;Button tab=button(names[i]);tab.setAlpha(i==0?1f:.65f);nav.addView(tab,new LinearLayout.LayoutParams(0,-2,1));tab.setOnClickListener(v->{selectedPage=n;for(int k=0;k<nav.getChildCount();k++)nav.getChildAt(k).setAlpha(k==n?1f:.65f);capturePage.setVisibility(n==0?View.VISIBLE:View.GONE);recordsScroll.setVisibility(n==1?View.VISIBLE:View.GONE);deviceScroll.setVisibility(n==2?View.VISIBLE:View.GONE);if(n==1)refreshRecords();});}
        root.addView(nav);recordsScroll.setVisibility(View.GONE);deviceScroll.setVisibility(View.GONE);setContentView(root);uiHandler.post(tick);
    }

    private final Runnable tick=new Runnable(){public void run(){
        long now=SystemClock.elapsedRealtimeNanos();
        recordButton.setEnabled(!finalizing && (recording!=null || cameraReady && bleConnected && now-lastFrameNs<2_000_000_000L));
        if(recording!=null){long sec=(now-recordingStartNs)/1_000_000_000L;timerText.setText(String.format(Locale.US,"● %02d:%02d · 正在同步采集",sec/60,sec%60));SessionRecorder current=session;if(current!=null&&current.error()!=null){statusText.setText("写入失败，正在结束采集");recording.stop();finalizing=true;}else if(!bleConnected||now-lastFrameNs>2_000_000_000L)metricsText.setText("IMU 数据中断 · 视频继续记录");}
        deviceText.setText((bleConnected?"PawLink-Test · 已连接":"项圈未连接")+"\n估算电量："+battery+" · 节点电压："+voltage+"\n"+power+"\n"+(batteryNs==0?"尚未收到电量":((now-batteryNs)/1_000_000_000L)+" 秒前更新（估算值）"));
        uiHandler.postDelayed(this,1000);
    }};

    private void refreshRecords(){
        recordsPage.removeAllViews();recordsPage.addView(CaptureUi.title(this,"采集记录"));recordsPage.addView(text("原始资料保留，复核结果单独存储",14,CaptureUi.MUTED));
        File dir=new File(getExternalFilesDir(null),"sessions");File[] all=dir.listFiles(File::isDirectory);if(all==null||all.length==0){recordsPage.addView(text("还没有记录。连接项圈后开始采集。",16,CaptureUi.INK));return;}
        Arrays.sort(all,Comparator.comparing(File::getName).reversed());for(File f:all){LinearLayout card=CaptureUi.card(this);card.addView(text(f.getName(),16,CaptureUi.INK));
            boolean active=session!=null&&f.equals(session.directory())||f.equals(savingDirectory);boolean deleting=deletingSessions.contains(f.getName());
            TextView summary=text(deleting?"正在删除…":active?"正在采集":"正在读取记录…",13,CaptureUi.MUTED);card.addView(summary);
            if(!deleting&&!active)fileExecutor.execute(()->{String value=recordSummary(f);runOnUiThread(()->{if(!isDestroyed())summary.setText(value);});});
            Button open=button("打开视频复核");open.setEnabled(!active&&!deleting&&recording==null&&new File(f,"manifest.json").exists());open.setOnClickListener(v->{Intent intent=new Intent(this,ReviewActivity.class);intent.putExtra(ReviewActivity.EXTRA_SESSION_NAME,f.getName());startActivity(intent);});LinearLayout actions=new LinearLayout(this);actions.addView(open,new LinearLayout.LayoutParams(0,-2,2));
            Button delete=button("删除");delete.setTextColor(Color.rgb(160,50,43));delete.setEnabled(!active&&!deleting&&!f.equals(exportingDirectory));delete.setOnClickListener(v->confirmDeleteSession(f));actions.addView(delete,new LinearLayout.LayoutParams(0,-2,1));card.addView(actions);Button archive=button("导出全部原始与复核资料");archive.setEnabled(!active&&!deleting&&exportingDirectory==null);archive.setOnClickListener(v->exportRecord(f));card.addView(archive);recordsPage.addView(card);}
    }

    private String recordSummary(File dir){
        try{org.json.JSONObject m=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new File(dir,"manifest.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));String kind=m.optString("capture_mode").equals("hand_test")?"手持测试":"猫："+m.optString("cat_id","未知");String state=m.optString("state");String status=state.equals("complete")?"待复核":state.equals("recording")?"异常中断 / 未完成落盘":"异常结束";File draft=new File(dir,"review-v3.json");if(draft.isFile()){org.json.JSONObject j=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(draft.toPath()),java.nio.charset.StandardCharsets.UTF_8));org.json.JSONArray a=j.optJSONArray("segments");int confirmed=0;long usableMs=0;if(a!=null)for(int i=0;i<a.length();i++){org.json.JSONObject x=a.getJSONObject(i);if(x.optBoolean("confirmed")){confirmed++;if(!x.optBoolean("excluded")&&CaptureUi.trainable(x.optString("label")))usableMs+=x.optLong("end_video_ms")-x.optLong("start_video_ms");}}status+=" · 已确认 "+confirmed+"/"+(a==null?0:a.length())+" 个标注区间 · 可用行为 "+String.format(Locale.US,"%.1f",usableMs/1000.0)+" 秒（不代表全视频已复核）";}return kind+" · "+status;}catch(Exception e){return "资料不完整，可尝试打开或导出";}
    }

    private void exportRecord(File directory){if(exportingDirectory!=null)return;exportingDirectory=directory;refreshRecords();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,directory.getName()+"-raw.zip");startActivityForResult(i,42);}
    @Override protected void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);if(request!=42)return;File directory=exportingDirectory;if(result!=RESULT_OK||intent==null||intent.getData()==null){exportingDirectory=null;refreshRecords();return;}android.net.Uri uri=intent.getData();fileExecutor.execute(()->{String message;try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(getContentResolver().openOutputStream(uri))){archiveTree(zip,directory,"");message="资料已导出";}catch(Exception e){message="导出失败："+e.getMessage();}String m=message;runOnUiThread(()->{exportingDirectory=null;if(!isDestroyed()){refreshRecords();Toast.makeText(this,m,Toast.LENGTH_LONG).show();}});});}
    private void archiveTree(java.util.zip.ZipOutputStream zip,File directory,String prefix)throws IOException{File[] files=directory.listFiles();if(files==null)throw new IOException("目录无法读取");for(File f:files){if(java.nio.file.Files.isSymbolicLink(f.toPath())||f.getName().endsWith(".tmp"))continue;if(f.isDirectory())archiveTree(zip,f,prefix+f.getName()+"/");else{zip.putNextEntry(new java.util.zip.ZipEntry(prefix+f.getName()));try(java.io.InputStream in=new java.io.FileInputStream(f)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)zip.write(b,0,n);}zip.closeEntry();}}}

    private void confirmDeleteSession(File directory) {
        SessionRecorder current=session;
        if (deletingSessions.contains(directory.getName()) || current!=null&&directory.equals(current.directory())||directory.equals(savingDirectory)) {
            Toast.makeText(this,"正在采集或删除中的记录不能删除",Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除这条采集记录？")
                .setMessage(directory.getName()+"\n\n将永久删除本条记录的视频、IMU、标签及所有复核版本，无法撤销。已导出到其他位置的 ZIP 不受影响。")
                .setNegativeButton("取消",null)
                .setPositiveButton("永久删除",(dialog,which)->deleteSession(directory))
                .show();
    }

    private void deleteSession(File directory) {
        SessionRecorder current=session;
        File active=current==null?savingDirectory:current.directory();
        if (directory.equals(active) || directory.equals(exportingDirectory) || !deletingSessions.add(directory.getName())) return;
        refreshRecords();
        File root=new File(getExternalFilesDir(null),"sessions");
        new Thread(()->{
            String message;
            try {SessionStorage.deleteSession(root,directory,active);message="采集记录已删除";}
            catch(IOException error){message="未能完整删除："+error.getMessage()+"。剩余文件仍保留，可重试。";}
            String result=message;
            runOnUiThread(()->{deletingSessions.remove(directory.getName());if(isDestroyed()||isFinishing())return;refreshRecords();Toast.makeText(this,result,Toast.LENGTH_LONG).show();});
        },"pawlink-delete").start();
    }

    @Override protected void onResume(){super.onResume();if(recordsPage!=null&&selectedPage==1)refreshRecords();}

    @Override public void onEvidence(String kind,String value,long ns){
        SessionRecorder current=session;if(current!=null)current.recordEvidence(kind,value,ns);
        runOnUiThread(()->{try{
            if(kind.equals("battery")){int n=Integer.parseInt(value);battery=n<=100?n+"%":"未知";batteryNs=ns;}
            if(kind.equals("voltage")){int n=Integer.parseInt(value);voltage=n>=2500&&n<=4500?String.format(Locale.US,"%.2f V",n/1000.0):"无效";if(n<2500||n>4500)battery="未知";}
            if(kind.equals("status")){power=value.contains("PWR=NOT_CHARGING")?"未在充电（不代表已充满）":value.contains("PWR=CHARGING")?"正在充电":power;}
            if(kind.equals("prediction")){String[] p=value.split(",");if(p.length>=5){prediction="设备："+CaptureUi.name(p[4])+" · 候选";if(p[4].equals("collar_shake"))prediction+="（动画保持旧动作）";predictionText.setText(prediction);}}
        }catch(RuntimeException ignored){statusText.setText("收到无法解析的设备状态");}});
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label) {
        Button button = CaptureUi.button(this,label,false);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void requestRequiredPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
        };
        boolean missing = false;
        for (String permission : permissions) {
            missing |= ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED;
        }
        if (missing) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSIONS_REQUEST);
        } else {
            startCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST) {
            boolean allGranted = true;
            for (int result : grantResults) allGranted &= result == PackageManager.PERMISSION_GRANTED;
            if (allGranted) startCamera();
            else statusText.setText("需要相机与附近设备权限才能采集");
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                ViewPort viewPort = previewView.getViewPort();
                if (viewPort == null) {
                    previewView.post(this::startCamera);
                    return;
                }
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                cameraRecorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build();
                VideoCapture<Recorder> videoCapture = VideoCapture.withOutput(cameraRecorder);

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                try {
                    visionAnalyzer = new VisionAnalyzer(this, new VisionAnalyzer.Listener() {
                        @Override
                        public void onVisionResult(VisionAnalyzer.Result result) {
                            handleVisionResult(result);
                        }

                        @Override
                        public void onVisionError(String message) {
                            runOnUiThread(() -> {
                                SessionRecorder current=session;if(current!=null)try{current.setLabel("unknown","vision_error");lastAutoLabel="unknown";}catch(IOException ignored){}
                                detectionOverlay.clear();
                                visionText.setText("视觉不可用：" + message);
                            });
                        }
                    });
                } catch (RuntimeException error) {
                    visionText.setText("视觉模型初始化失败");
                }
                analysis.setAnalyzer(cameraExecutor, image -> {
                    SessionRecorder current = session;
                    if (current != null) {
                        current.recordFrame(image.getImageInfo().getTimestamp(),
                                SystemClock.elapsedRealtimeNanos());
                    }
                    VisionAnalyzer analyzer = visionAnalyzer;
                    if (analyzer != null && !handTest) analyzer.analyze(image);
                    else image.close();
                });

                provider.unbindAll();
                UseCaseGroup useCases = new UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(videoCapture)
                        .addUseCase(analysis)
                        .build();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCases);
                statusText.setText("相机已就绪，请连接项圈");
                cameraReady = true;
            } catch (Exception error) {
                statusText.setText("相机启动失败：" + error.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void toggleRecording() {
        if (recording != null) {
            finalizing=true;
            recordButton.setEnabled(false);
            statusText.setText("正在保存，请稍候…");
            recording.stop();
            return;
        }
        if (cameraRecorder == null || finalizing) return;
        if(!bleConnected || SystemClock.elapsedRealtimeNanos()-lastFrameNs>2_000_000_000L){Toast.makeText(this,"请先在设备页连接项圈并等待 IMU 数据",Toast.LENGTH_LONG).show();return;}
        if(getExternalFilesDir(null).getUsableSpace()<200L*1024*1024){Toast.makeText(this,"存储不足，请至少留出 200 MB",Toast.LENGTH_LONG).show();return;}
        try {
            received = 0;
            firstFrameNs = 0;
            manualOverrideActive = false;
            lastAutoLabel = "unknown";
            lastCatSeenNs = 0;
            if(!handTest&&catInput.getText().toString().trim().isEmpty()){Toast.makeText(this,"请填写猫 ID，或选择手持测试",Toast.LENGTH_LONG).show();return;}
            session = new SessionRecorder(this,handTest,catInput.getText().toString().trim(),(mountInput.getText().toString().trim().isEmpty()?"unknown":mountInput.getText().toString().trim()),bleClient.epoch());
            for(long[] p:bleClient.syncObservations())session.recordSync((int)p[0],p[1],p[2],p[3],p[4]);
            testMode.setEnabled(false);catInput.setEnabled(false);mountInput.setEnabled(false);
            recordingStartNs=SystemClock.elapsedRealtimeNanos();
            activeLabelText.setText("视觉候选：无法判断 · 待复核");
            FileOutputOptions output = new FileOutputOptions.Builder(session.videoFile()).build();
            recording = cameraRecorder.prepareRecording(this, output)
                    .start(ContextCompat.getMainExecutor(this), event -> {
                        if (event instanceof VideoRecordEvent.Start) {
                            SessionRecorder current = session;
                            if (current != null) {
                                current.markVideoStarted(SystemClock.elapsedRealtimeNanos());
                            }
                            recordButton.setText("结束并复核");
                            statusText.setText(bleConnected
                                    ? "正在自动粗标并同步采集视频与 IMU"
                                    : "正在自动粗标并录像；IMU 尚未连接");
                        } else if (event instanceof VideoRecordEvent.Finalize finalizeEvent) {
                            String reason = finalizeEvent.hasError()
                                    ? "video_error_" + finalizeEvent.getError() : "user_stop";
                            finishSession(reason);
                        }
                    });
        } catch (Exception error) {
            if(session!=null)try{session.close("start_error");}catch(IOException ignored){}
            recording=null;
            Toast.makeText(this, "无法开始采集：" + error.getMessage(), Toast.LENGTH_LONG).show();
            session = null;testMode.setEnabled(true);catInput.setEnabled(true);mountInput.setEnabled(true);
        }
    }

    private void finishSession(String reason) {
        SessionRecorder completed=session;savingDirectory=completed==null?null:completed.directory();session=null;recording=null;finalizing=true;statusText.setText("正在落盘，请稍候…");
        new Thread(()->{String failure=null;try{if(completed!=null)completed.close(reason);}catch(IOException e){failure=e.getMessage();}final String error=failure;runOnUiThread(()->{savingDirectory=null;finalizing=false;testMode.setEnabled(true);catInput.setEnabled(true);mountInput.setEnabled(true);recordButton.setText("开始采集");timerText.setText("00:00 · 视频 + 六轴 IMU");statusText.setText(error!=null?"保存不完整："+error:reason.equals("user_stop")?"已保存，可复核":"异常结束，已保留可用资料");if(error==null&&completed!=null&&!isFinishing()&&!isDestroyed()&&reason.equals("user_stop")){Intent i=new Intent(this,ReviewActivity.class);i.putExtra(ReviewActivity.EXTRA_SESSION_NAME,completed.directory().getName());startActivity(i);}});},"pawlink-finalize").start();
    }

    private void selectLabel(String label) {
        selectLabel(label, "manual");
    }

    private void selectLabel(String label, String source) {
        activeLabelText.setText("标签：" + label);
        SessionRecorder current = session;
        if (current != null) {
            manualOverrideActive = true;
            try {
                current.setLabel(label, source);
            } catch (IOException error) {
                Toast.makeText(this, "标签写入失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void handleVisionResult(VisionAnalyzer.Result result) {
        SessionRecorder current = session;
        if (current != null) {
            current.recordVision(result.elapsedTimeNs, result.catVisible, result.catScore,
                    result.motionScore, result.suggestion, result.suggestionConfidence,
                    result.topCategory, result.topScore);
            applyAutomaticLabel(current, result);
        }
        lastVisionSuggestion = result.suggestion;
        String text;
        if (!result.catVisible) {
            if (result.topScore > 0f) {
                text = String.format(Locale.US, "视觉：未检测到猫 · 最高 %s %.0f%%",
                        result.topCategory, result.topScore * 100f);
            } else {
                text = "视觉：未检测到猫 · 无候选目标";
            }
        } else if ("unknown".equals(result.suggestion)) {
            text = String.format(Locale.US, "视觉：猫 %.0f%% · 正在观察动作",
                    result.catScore * 100f);
        } else {
            text = String.format(Locale.US, "猫检测 %.0f%% · 动作候选 %s",result.catScore*100f,result.suggestion);
        }
        runOnUiThread(() -> {
            detectionOverlay.setDetections(result.boxes, result.imageWidth,
                    result.imageHeight);
            visionText.setText(text);
        });
    }

    private void applyAutomaticLabel(SessionRecorder current, VisionAnalyzer.Result result) {
        if (manualOverrideActive) return;
        boolean ambiguous=result.boxes.stream().filter(b->b.category.equals("cat")).count()>1;
        if(ambiguous!=multipleCats){multipleCats=ambiguous;current.recordEvidence("identity",ambiguous?"multiple_cats":"single_or_no_cat",result.elapsedTimeNs);}
        if(ambiguous){try{if(!lastAutoLabel.equals("unknown")){current.setLabel("unknown","identity_uncertain");lastAutoLabel="unknown";}runOnUiThread(()->activeLabelText.setText("多猫画面 · 身份待核对"));}catch(IOException ignored){}return;}

        if (result.catVisible) lastCatSeenNs = result.elapsedTimeNs;

        String candidate = null;
        if ("rest".equals(result.suggestion) || "locomotion".equals(result.suggestion)) {
            candidate = result.suggestion;
        } else {
            candidate = "unknown";
        }
        if (candidate == null || candidate.equals(lastAutoLabel)) return;

        try {
            current.setLabel(candidate, "vision_auto");
            lastAutoLabel = candidate;
            String applied = candidate;
            runOnUiThread(() -> activeLabelText.setText("视觉候选：" + CaptureUi.name(applied) + " · 待复核"));
        } catch (IOException error) {
            runOnUiThread(() -> Toast.makeText(this,
                    "自动粗标签写入失败", Toast.LENGTH_SHORT).show());
        }
    }

    private void resumeAutomaticLabels() {
        if (session == null) {
            Toast.makeText(this, "请先开始采集", Toast.LENGTH_SHORT).show();
            return;
        }
        manualOverrideActive = false;
        lastAutoLabel = "";
        activeLabelText.setText("标签：等待自动粗标");
        Toast.makeText(this, "已恢复自动粗分类", Toast.LENGTH_SHORT).show();
    }

    private void openReviewPicker() {
        File sessionsDir = new File(getExternalFilesDir(null), "sessions");
        File[] sessions = sessionsDir.listFiles(file -> file.isDirectory()
                && new File(file, "video.mp4").isFile()
                && new File(file, "labels.csv").isFile());
        if (sessions == null || sessions.length == 0) {
            Toast.makeText(this, "还没有可复核的采集记录", Toast.LENGTH_SHORT).show();
            return;
        }
        Arrays.sort(sessions, Comparator.comparing(File::getName).reversed());
        String[] names = Arrays.stream(sessions).map(File::getName).toArray(String[]::new);
        new AlertDialog.Builder(this)
                .setTitle("选择采集记录")
                .setItems(names, (dialog, which) -> {
                    Intent intent = new Intent(this, ReviewActivity.class);
                    intent.putExtra(ReviewActivity.EXTRA_SESSION_NAME, names[which]);
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onStatus(String status) {
        runOnUiThread(() -> statusText.setText(status));
    }

    @Override
    public void onConnected(boolean connected) {

        bleConnected = connected;
        if(!connected){lastFrameNs=0;firstFrameNs=0;received=0;runOnUiThread(()->predictionText.setText("设备：连接中断，预测已过期"));}
        runOnUiThread(() -> connectButton.setText(connected ? "重新连接" : "连接项圈"));
    }

    @Override public void onEpoch(int epoch,String reason){SessionRecorder s=session;if(s!=null)s.setEpoch(epoch,reason);}
    @Override public void onSync(int epoch,long t1,long t2,long t3,long t4){boolean accepted=clock.observe(epoch,t1,t2,t3,t4);SessionRecorder s=session;if(s!=null)s.recordSync(epoch,t1,t2,t3,t4);ClockSync.Mapping m=clock.mapping(epoch);runOnUiThread(()->{if(accepted&&m!=null)statusText.setText("设备时钟已同步 · 往返估计误差 ±"+(m.uncertaintyNs/1_000_000)+" ms；视频需复核校准");});}

    @Override
    public void onFrame(ImuFrame frame, long receivedElapsedNs) {
        if(lastFrameNs==0)runOnUiThread(()->statusText.setText("项圈已就绪 · 视频与 IMU 可同步采集"));
        lastFrameNs=receivedElapsedNs;

        lastDeviceMs=frame.deviceTimeMs;
        SessionRecorder current = session;
        if (current != null) current.recordImu(frame, receivedElapsedNs);
        received++;
        if (firstFrameNs == 0) firstFrameNs = receivedElapsedNs;
        if (receivedElapsedNs - lastUiUpdateNs > 500_000_000L) {
            lastUiUpdateNs = receivedElapsedNs;
            double seconds = Math.max(0.001, (receivedElapsedNs - firstFrameNs) / 1e9);
            double hz = received <= 1 ? 0.0 : (received - 1) / seconds;
            String value = String.format(Locale.US,
                    "IMU：%.1f Hz · 已收 %d 帧 · 丢失 %d", hz, received, current==null?0:current.dropped());
            runOnUiThread(() -> metricsText.setText(value));
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (recording != null) recording.stop();
        bleClient.disconnect();
        if (visionAnalyzer != null) visionAnalyzer.close();
        cameraExecutor.shutdown();
        fileExecutor.shutdown();
        super.onDestroy();
    }
}
