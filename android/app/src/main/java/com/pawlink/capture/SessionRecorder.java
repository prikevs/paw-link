package com.pawlink.capture;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class SessionRecorder {
    private final File directory;
    private final long startedWallMs;
    private final long startedElapsedNs;
    private long videoStartedElapsedNs;
    private BufferedWriter imuWriter;
    private BufferedWriter frameWriter;
    private BufferedWriter labelWriter;
    private BufferedWriter visionWriter;
    private BufferedWriter evidenceWriter;
    private boolean closed;
    private String writeError;
    private int epoch;
    private String label = "unknown";
    private String labelSource = "system";
    private long samples;
    private long dropped;
    private Long lastSequence;

    SessionRecorder(Context context) throws IOException {
        String name = "session-" + ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        File sessions = new File(context.getExternalFilesDir(null), "sessions");
        directory = new File(sessions, name);
        if (!directory.mkdirs()) throw new IOException("无法创建会话目录");
        startedWallMs = System.currentTimeMillis();
        startedElapsedNs = SystemClock.elapsedRealtimeNanos();
        imuWriter = new BufferedWriter(new FileWriter(new File(directory, "imu.csv")));
        frameWriter = new BufferedWriter(new FileWriter(new File(directory, "frames.csv")));
        labelWriter = new BufferedWriter(new FileWriter(new File(directory, "labels.csv")));
        visionWriter = new BufferedWriter(new FileWriter(new File(directory, "vision.csv")));
        imuWriter.write("host_time_ns,elapsed_time_ns,device_time_ms,sequence,label,label_source,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps\n");
        frameWriter.write("camera_time_ns,elapsed_time_ns\n");
        labelWriter.write("elapsed_time_ns,wall_time_ms,label,source\n");
        visionWriter.write("elapsed_time_ns,cat_visible,cat_score,motion_score,suggestion,suggestion_confidence,top_category,top_score\n");
        evidenceWriter = new BufferedWriter(new FileWriter(new File(directory, "evidence.csv")));
        evidenceWriter.write("elapsed_time_ns,epoch,kind,payload\n");
        setLabel("unknown", "system");
        writeManifest("recording");
    }

    File videoFile() {
        return new File(directory, "video.mp4");
    }

    File directory() {
        return directory;
    }

    synchronized void markVideoStarted(long elapsedNs) {
        videoStartedElapsedNs = elapsedNs;
    }

    synchronized void setLabel(String newLabel) throws IOException {
        setLabel(newLabel, "manual");
    }

    synchronized void setLabel(String newLabel, String source) throws IOException {
        if (closed) return;
        label = newLabel;
        labelSource = source;
        labelWriter.write(String.format(Locale.US, "%d,%d,%s,%s\n",
                SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(), newLabel, source));
        labelWriter.flush();
    }

    synchronized void recordVision(long elapsedTimeNs, boolean catVisible, float catScore,
                                   float motionScore, String suggestion,
                                   float suggestionConfidence, String topCategory,
                                   float topScore) {
        if (closed) return;
        try {
            visionWriter.write(String.format(Locale.US,
                    "%d,%s,%.5f,%.5f,%s,%.5f,%s,%.5f\n",
                    elapsedTimeNs, catVisible, catScore, motionScore, suggestion,
                    suggestionConfidence, topCategory, topScore));
            visionWriter.flush();
        } catch (IOException error) {
            writeError = error.getMessage();
        }
    }

    synchronized void recordFrame(long cameraTimeNs, long elapsedTimeNs) {
        if (closed) return;
        try {
            frameWriter.write(cameraTimeNs + "," + elapsedTimeNs + "\n");
        } catch (IOException error) {
            writeError = error.getMessage();
        }
    }

    synchronized void recordImu(ImuFrame frame, long elapsedTimeNs) {
        if (closed) return;
        try {
            if (lastSequence != null) {
                long gap = (frame.sequence - lastSequence) & 0xFFFFFFFFL;
                if (gap > 1 && gap < 0x80000000L) dropped += gap - 1;
            }
            lastSequence = frame.sequence;
            samples++;
            imuWriter.write(String.format(Locale.US,
                    "%d,%d,%d,%d,%s,%s,%.6f,%.6f,%.6f,%.4f,%.4f,%.4f\n",
                    System.currentTimeMillis() * 1_000_000L,
                    elapsedTimeNs, frame.deviceTimeMs, frame.sequence, label, labelSource,
                    frame.ax, frame.ay, frame.az, frame.gx, frame.gy, frame.gz));
            if (samples % 50 == 0) imuWriter.flush();
        } catch (IOException error) {
            writeError = error.getMessage();
        }
    }

    synchronized String error() { return writeError; }
    synchronized long samples() { return samples; }
    synchronized long dropped() { return dropped; }
    synchronized void newEpoch(String reason) { epoch++; lastSequence=null; recordEvidence("connection",reason,SystemClock.elapsedRealtimeNanos()); }
    synchronized void recordEvidence(String kind,String value,long ns) {
        if(closed)return;
        try { evidenceWriter.write(ns+","+epoch+","+kind+",\""+value.replace("\"","\"\"").replace("\n"," ").replace("\r"," ")+"\"\n"); evidenceWriter.flush(); }
        catch(IOException error){writeError=error.getMessage();}
    }
    synchronized void close(String stopReason) throws IOException {
        if(closed)return;
        closed=true;
        IOException failure=null;
        for(BufferedWriter w:new BufferedWriter[]{imuWriter,frameWriter,labelWriter,visionWriter,evidenceWriter}) {
            try { if(w!=null)w.close(); } catch(IOException e){failure=e;}
        }
        writeManifest(failure!=null||writeError!=null?"write_error":stopReason);
        if(failure!=null)throw failure;
        if(writeError!=null)throw new IOException(writeError);
    }
    private void writeManifest(String stopReason) throws IOException {
        try {
            JSONObject manifest = new JSONObject();
            manifest.put("schema_version", 2);
            manifest.put("taxonomy_version", "pawlink-actions-v1");
            manifest.put("sync_status", "uncalibrated_receive_time");
            manifest.put("state", stopReason.equals("recording") ? "recording" : stopReason.equals("user_stop") ? "complete" : "interrupted");
            manifest.put("mounting", "usb_left_components_out_long_axis_along_collar");
            manifest.put("started_wall_ms", startedWallMs);
            manifest.put("started_elapsed_ns", startedElapsedNs);
            manifest.put("video_started_elapsed_ns",
                    videoStartedElapsedNs == 0 ? startedElapsedNs : videoStartedElapsedNs);
            manifest.put("ended_wall_ms", System.currentTimeMillis());
            manifest.put("ended_elapsed_ns", SystemClock.elapsedRealtimeNanos());
            manifest.put("stop_reason", stopReason);
            manifest.put("imu_samples", samples);
            manifest.put("imu_dropped", dropped);
            manifest.put("imu_nominal_hz", 50);
            manifest.put("ble_protocol", "pawlink-v1-20byte");
            manifest.put("vision_model", "efficientdet_lite2_int8_coco_cat_person");
            manifest.put("vision_policy", "auto_provisional_with_human_review");
            manifest.put("app_version", "0.6.1");
            try (BufferedWriter writer = new BufferedWriter(
                    new FileWriter(new File(directory, "manifest.json")))) {
                writer.write(manifest.toString(2));
                writer.write("\n");
            }
        } catch (JSONException error) {
            throw new IOException("无法生成会话清单", error);
        }
    }
}
