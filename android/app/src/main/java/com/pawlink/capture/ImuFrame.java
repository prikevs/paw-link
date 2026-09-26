package com.pawlink.capture;

final class ImuFrame {
    final long sequence;
    final long deviceTimeMs;
    final float ax;
    final float ay;
    final float az;
    final float gx;
    final float gy;
    final float gz;

    ImuFrame(long sequence, long deviceTimeMs, float ax, float ay, float az,
             float gx, float gy, float gz) {
        this.sequence = sequence;
        this.deviceTimeMs = deviceTimeMs;
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.gx = gx;
        this.gy = gy;
        this.gz = gz;
    }
}
