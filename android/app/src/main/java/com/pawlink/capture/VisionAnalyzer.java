package com.pawlink.capture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;

import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;

import java.util.ArrayList;
import java.util.List;

final class VisionAnalyzer implements AutoCloseable {
    static final class DetectionBox {
        final RectF bounds;
        final String category;
        final float score;

        DetectionBox(RectF bounds, String category, float score) {
            this.bounds = bounds;
            this.category = category;
            this.score = score;
        }
    }

    static final class Result {
        final long elapsedTimeNs;
        final boolean catVisible;
        final float catScore;
        final float motionScore;
        final String suggestion;
        final float suggestionConfidence;
        final String topCategory;
        final float topScore;
        final List<DetectionBox> boxes;
        final int imageWidth;
        final int imageHeight;

        Result(long elapsedTimeNs, boolean catVisible, float catScore, float motionScore,
               String suggestion, float suggestionConfidence, String topCategory,
               float topScore, List<DetectionBox> boxes, int imageWidth,
               int imageHeight) {
            this.elapsedTimeNs = elapsedTimeNs;
            this.catVisible = catVisible;
            this.catScore = catScore;
            this.motionScore = motionScore;
            this.suggestion = suggestion;
            this.suggestionConfidence = suggestionConfidence;
            this.topCategory = topCategory;
            this.topScore = topScore;
            this.boxes = boxes;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }
    }

    interface Listener {
        void onVisionResult(Result result);
        void onVisionError(String message);
    }

    private static final long ANALYSIS_INTERVAL_NS = 200_000_000L;
    private final ObjectDetector detector;
    private final Listener listener;
    private long lastAnalyzedNs;
    private float previousCenterX;
    private float previousCenterY;
    private float previousArea;
    private boolean hasPreviousBox;
    private float motionEma;
    private int lowMotionFrames;
    private int highMotionFrames;

    VisionAnalyzer(Context context, Listener listener) {
        this.listener = listener;
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath("efficientdet_lite2.tflite")
                .build();
        ObjectDetector.ObjectDetectorOptions options =
                ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.VIDEO)
                        .setScoreThreshold(0.20f)
                        .setMaxResults(10)
                        .setCategoryAllowlist(List.of("cat", "person"))
                        .build();
        detector = ObjectDetector.createFromOptions(context, options);
    }

    void analyze(ImageProxy image) {
        long cameraTimeNs = image.getImageInfo().getTimestamp();
        if (cameraTimeNs - lastAnalyzedNs < ANALYSIS_INTERVAL_NS) {
            image.close();
            return;
        }
        lastAnalyzedNs = cameraTimeNs;
        int rotation = image.getImageInfo().getRotationDegrees();
        boolean imageClosed = false;
        try {
            Rect cropRect = new Rect(image.getCropRect());
            // CameraX removes any per-row padding when converting the RGBA plane.
            // Direct copyPixelsFromBuffer() corrupts frames when rowStride > width * 4.
            Bitmap fullBitmap = image.toBitmap();
            image.close();
            imageClosed = true;

            Bitmap croppedBitmap = fullBitmap;
            if (cropRect.left != 0 || cropRect.top != 0
                    || cropRect.width() != fullBitmap.getWidth()
                    || cropRect.height() != fullBitmap.getHeight()) {
                croppedBitmap = Bitmap.createBitmap(fullBitmap, cropRect.left, cropRect.top,
                        cropRect.width(), cropRect.height());
            }
            Bitmap modelBitmap = croppedBitmap;
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                modelBitmap = Bitmap.createBitmap(croppedBitmap, 0, 0,
                        croppedBitmap.getWidth(), croppedBitmap.getHeight(), matrix, true);
            }

            MPImage mpImage = new BitmapImageBuilder(modelBitmap).build();
            long elapsedNs = SystemClock.elapsedRealtimeNanos();
            ObjectDetectorResult detection = detector.detectForVideo(
                    mpImage, cameraTimeNs / 1_000_000L);
            listener.onVisionResult(toResult(detection, elapsedNs, modelBitmap.getWidth(),
                    modelBitmap.getHeight()));
        } catch (RuntimeException error) {
            if (!imageClosed) image.close();
            listener.onVisionError(error.getMessage() == null
                    ? "视觉推理失败" : error.getMessage());
        }
    }

    private Result toResult(ObjectDetectorResult detection, long elapsedNs,
                            int imageWidth, int imageHeight) {
        com.google.mediapipe.tasks.components.containers.Detection bestCat = null;
        List<DetectionBox> boxes = new ArrayList<>();
        String topCategory = "none";
        float topScore = 0f;
        for (com.google.mediapipe.tasks.components.containers.Detection candidate
                : detection.detections()) {
            if (candidate.categories().isEmpty()) continue;
            String category = candidate.categories().get(0).categoryName();
            float candidateScore = candidate.categories().get(0).score();
            String displayCategory = category == null || category.isBlank()
                    ? "class_" + candidate.categories().get(0).index() : category;
            boxes.add(new DetectionBox(new RectF(candidate.boundingBox()),
                    displayCategory, candidateScore));
            if (candidateScore > topScore) {
                topCategory = category == null || category.isBlank() ? "class_"
                        + candidate.categories().get(0).index() : category;
                topScore = candidateScore;
            }
            if ("cat".equalsIgnoreCase(category)
                    && (bestCat == null
                    || candidateScore > bestCat.categories().get(0).score())) {
                bestCat = candidate;
            }
        }

        if (bestCat == null) {
            hasPreviousBox = false;
            motionEma = 0f;
            lowMotionFrames = 0;
            highMotionFrames = 0;
            return new Result(elapsedNs, false, 0f, 0f, "unknown", 0f,
                    topCategory, topScore, boxes, imageWidth, imageHeight);
        }

        com.google.mediapipe.tasks.components.containers.Detection cat = bestCat;
        float score = cat.categories().isEmpty() ? 0f : cat.categories().get(0).score();
        RectF box = cat.boundingBox();
        float centerX = box.centerX() / imageWidth;
        float centerY = box.centerY() / imageHeight;
        float area = Math.max(0.0001f,
                (box.width() / imageWidth) * (box.height() / imageHeight));
        float instantaneousMotion = 0f;
        if (hasPreviousBox) {
            float dx = centerX - previousCenterX;
            float dy = centerY - previousCenterY;
            float translation = (float) Math.sqrt(dx * dx + dy * dy);
            float scaleChange = Math.abs((float) Math.log(area / previousArea));
            instantaneousMotion = translation + 0.25f * scaleChange;
            motionEma = 0.65f * motionEma + 0.35f * instantaneousMotion;
        }
        previousCenterX = centerX;
        previousCenterY = centerY;
        previousArea = area;
        hasPreviousBox = true;

        if (motionEma < 0.012f) {
            lowMotionFrames++;
            highMotionFrames = 0;
        } else if (motionEma > 0.045f) {
            highMotionFrames++;
            lowMotionFrames = 0;
        } else {
            lowMotionFrames = Math.max(0, lowMotionFrames - 1);
            highMotionFrames = Math.max(0, highMotionFrames - 1);
        }

        String suggestion = "unknown";
        float confidence = 0f;
        if (lowMotionFrames >= 8) {
            suggestion = "rest";
            confidence = Math.min(score, 0.55f + lowMotionFrames * 0.03f);
        } else if (highMotionFrames >= 3) {
            suggestion = "locomotion";
            confidence = Math.min(score, 0.65f + highMotionFrames * 0.04f);
        }
        return new Result(elapsedNs, true, score, motionEma, suggestion, confidence,
                topCategory, topScore, boxes, imageWidth, imageHeight);
    }

    @Override
    public void close() {
        detector.close();
    }
}
