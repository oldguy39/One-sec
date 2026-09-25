package com.john.onesecoff;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Turns the screen off ~1 second after the phone is laid down flat (face up or face down).
 *
 * Battery design:
 *  - The motion sensor is switched on ONLY while the screen is on.
 *    When the screen is off, this app does nothing at all.
 *  - Sensor runs at the slow "normal" rate (about 5 readings per second).
 *  - No foreground notification, no timers, no wake locks.
 */
public class LockService extends AccessibilityService implements SensorEventListener {

    // ---- Tuning (change these if needed) ----
    private static final long  STILL_MS  = 1000;  // how long it must lie still before screen off
    private static final float STILL_TOL = 0.15f; // jitter allowed while "still" (m/s^2)
    private static final float FLAT_Z    = 9.3f;  // |z| above this = lying flat (within ~18 degrees)
    private static final float FLAT_XY   = 2.5f;  // sideways tilt allowance
    private static final float MOVE_TOL  = 0.8f;  // movement that counts as "picked up / being set down"

    private SensorManager sensorManager;
    private Sensor accel;
    private boolean listening = false;
    private boolean armed = false;       // only true after the phone has moved or been tilted
    private boolean haveBase = false;
    private float bx, by, bz;
    private long stillSinceNs = 0;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) startWatching();
            else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) stopWatching();
        }
    };

    @Override
    protected void onServiceConnected() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isInteractive()) startWatching();
    }

    private void startWatching() {
        if (listening || accel == null) return;
        armed = false;
        haveBase = false;
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL);
        listening = true;
    }

    private void stopWatching() {
        if (!listening) return;
        sensorManager.unregisterListener(this);
        listening = false;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        boolean flat = Math.abs(z) > FLAT_Z && Math.hypot(x, y) < FLAT_XY;

        if (!haveBase) {
            bx = x; by = y; bz = z;
            stillSinceNs = e.timestamp;
            haveBase = true;
            if (!flat) armed = true;
            return;
        }

        float d = Math.max(Math.abs(x - bx), Math.max(Math.abs(y - by), Math.abs(z - bz)));

        // Arm only after real movement or tilt, so waking the phone while it's
        // already on the table (to read it there) does NOT shut it off.
        if (d > MOVE_TOL || !flat) armed = true;

        if (d > STILL_TOL || !flat) {
            bx = x; by = y; bz = z;
            stillSinceNs = e.timestamp;
            return;
        }

        if (armed && e.timestamp - stillSinceNs >= STILL_MS * 1_000_000L) {
            stopWatching();
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); // fingerprint/face unlock still works
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        stopWatching();
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
