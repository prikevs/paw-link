package com.pawlink.capture;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;
import com.google.mediapipe.tasks.components.containers.Detection;
import java.util.Arrays;
import java.util.List;
import java.lang.reflect.Method;

/** On-device check. External fixtures are downloaded separately and never shipped. */
public final class ModelSmokeInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private ObjectDetector detector(Context context, String model, boolean filtered) {
        BaseOptions.Builder base=BaseOptions.builder();
        if (model.startsWith("model-test/")) {
            // MediaPipe's native asset manager uses the target APK, not the test APK.
            try (java.io.InputStream stream=context.getAssets().open(model)) {
                byte[] bytes=stream.readAllBytes();
                java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocateDirect(bytes.length);
                buffer.put(bytes).rewind(); base.setModelAssetBuffer(buffer);
            } catch (java.io.IOException error) { throw new RuntimeException(error); }
        } else base.setModelAssetPath(model);
        ObjectDetector.ObjectDetectorOptions.Builder options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base.build())
            .setRunningMode(RunningMode.VIDEO).setScoreThreshold(0.20f).setMaxResults(10);
        if (filtered) options.setCategoryAllowlist(List.of("cat", "person"));
        return ObjectDetector.createFromOptions(context, options.build());
    }
    private ObjectDetectorResult detect(ObjectDetector detector, Bitmap bitmap, long timestamp) {
        MPImage image = new BitmapImageBuilder(bitmap.copy(Bitmap.Config.ARGB_8888, false)).build();
        try { return detector.detectForVideo(image, timestamp); }
        finally { image.close(); }
    }
    private void validate(ObjectDetectorResult result, int width, int height, boolean filtered) {
        boolean cat = false;
        for (Detection item : result.detections()) {
            String label = item.categories().get(0).categoryName();
            float score = item.categories().get(0).score();
            RectF box = item.boundingBox();
            require(Float.isFinite(score) && score >= .2f && score <= 1f, "invalid score");
            require(Float.isFinite(box.left) && Float.isFinite(box.top)
                && Float.isFinite(box.right) && Float.isFinite(box.bottom)
                && box.width() > 0 && box.height() > 0, "invalid box");
            require(box.right > 0 && box.bottom > 0 && box.left < width && box.top < height,
                "box does not intersect image");
            if (filtered) require("cat".equals(label) || "person".equals(label), "allowlist ignored");
            cat |= "cat".equals(label);
            report.append(label).append(' ').append(score).append(' ').append(box).append('\n');
        }
        require(cat, "cat not detected");
    }
    private RectF bestCat(ObjectDetectorResult result) {
        for (Detection d : result.detections())
            if ("cat".equals(d.categories().get(0).categoryName())) return new RectF(d.boundingBox());
        throw new AssertionError("missing cat");
    }
    private void benchmark(Context context, String model, Bitmap image, String label) {
        try (ObjectDetector detector = detector(context, model, true)) {
            double[] times = new double[20];
            for (int i=0; i<25; i++) {
                long start = SystemClock.elapsedRealtimeNanos();
                ObjectDetectorResult result = detect(detector,image,200L*(i+1));
                double ms = (SystemClock.elapsedRealtimeNanos()-start)/1e6;
                require(!result.detections().isEmpty(), "empty benchmark result");
                if (i>=5) times[i-5]=ms;
            }
            Arrays.sort(times);
            report.append(label).append(" ms: median=").append((times[9]+times[10])/2)
                .append(" p95=").append(times[18]).append(" max=").append(times[19]).append('\n');
        }
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            Bitmap image;
            try (java.io.InputStream stream = getContext().getAssets().open("model-test/cats_and_dogs.jpg")) {
                image=BitmapFactory.decodeStream(stream);
            }
            require(image!=null,"fixture decode failed");
            report.append("Image ").append(image.getWidth()).append('x').append(image.getHeight()).append('\n');
            ObjectDetectorResult full;
            try (ObjectDetector detector=detector(getTargetContext(),"efficientdet_lite2.tflite",false)) {
                full=detect(detector,image,200); validate(full,image.getWidth(),image.getHeight(),false);
                require(full.detections().stream().anyMatch(d -> "dog".equals(d.categories().get(0).categoryName())),
                    "unfiltered dog missing");
            }
            try (ObjectDetector detector=detector(getTargetContext(),"efficientdet_lite2.tflite",true)) {
                full=detect(detector,image,200); validate(full,image.getWidth(),image.getHeight(),true);
                Bitmap half=Bitmap.createScaledBitmap(image,image.getWidth()/2,image.getHeight()/2,true);
                ObjectDetectorResult small=detect(detector,half,400);
                validate(small,half.getWidth(),half.getHeight(),true);
                float sx=image.getWidth()/(float)half.getWidth(),sy=image.getHeight()/(float)half.getHeight();
                // Match by geometry: the highest-confidence cat may change after resizing.
                for (Detection original : full.detections()) {
                    if (!"cat".equals(original.categories().get(0).categoryName())) continue;
                    RectF a=original.boundingBox(); float bestIou=0f;
                    for (Detection candidate : small.detections()) {
                        if (!"cat".equals(candidate.categories().get(0).categoryName())) continue;
                        RectF b=new RectF(candidate.boundingBox());
                        b.set(b.left*sx,b.top*sy,b.right*sx,b.bottom*sy);
                        RectF intersection=new RectF(a);
                        if (!intersection.intersect(b)) continue;
                        float area=intersection.width()*intersection.height();
                        bestIou=Math.max(bestIou,area/(a.width()*a.height()+b.width()*b.height()-area));
                    }
                    require(bestIou>.65f,"coordinate scaling mismatch: "+bestIou);
                    report.append("Scaled-box IoU=").append(bestIou).append('\n');
                }
                half.recycle();
                Bitmap blank=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888);
                blank.eraseColor(android.graphics.Color.GRAY);
                require(detect(detector,blank,600).detections().isEmpty(),"blank image false positive");blank.recycle();
            }
            try (VisionAnalyzer analyzer=new VisionAnalyzer(getTargetContext(),new VisionAnalyzer.Listener(){
                public void onVisionResult(VisionAnalyzer.Result r) {}
                public void onVisionError(String error) {throw new AssertionError(error);}
            })) {
                Method convert=VisionAnalyzer.class.getDeclaredMethod("toResult",ObjectDetectorResult.class,long.class,int.class,int.class);
                convert.setAccessible(true);
                VisionAnalyzer.Result state=null;
                for(int i=0;i<10;i++) state=(VisionAnalyzer.Result)convert.invoke(analyzer,full,200000000L*i,image.getWidth(),image.getHeight());
                require(state.catVisible && "rest".equals(state.suggestion),"app state conversion failed");
                require(state.imageWidth==image.getWidth() && state.imageHeight==image.getHeight(),"image dimensions lost");
                report.append("App result conversion and stable-frame rest suggestion: PASS\n");
            }
            benchmark(getContext(),"model-test/previous.tflite",image,"Previous MediaPipe");
            benchmark(getTargetContext(),"efficientdet_lite2.tflite",image,"New Kaggle");
            image.recycle();
            output.putString("stream", "\nPASS\n"+report);finish(-1,output);
        } catch (Throwable error) {
            output.putString("stream","\nFAIL\n"+report+android.util.Log.getStackTraceString(error));finish(0,output);
        }
    }
}
