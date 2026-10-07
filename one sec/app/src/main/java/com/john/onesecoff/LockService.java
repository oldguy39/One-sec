package com.john.onesecoff;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Turns the screen off ~1 second after the phone is laid down flat (face up or face down).
 *
 * Floating icon:
 *  - A small round icon floats on screen while the service is switched on.
 *  - RED dot  = One Second Off is active.
 *  - GREY dot = paused (tap the icon to pause / resume).
 *  - Drag it with your finger to move it; it remembers where you put it.
 *
 * Battery design:
 *  - The motion sensor is switched on ONLY while the screen is on (and not paused).
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

    private SharedPreferences prefs;
    private boolean paused = false;
    private WindowManager wm;
    private Bubble bubble;
    private WindowManager.LayoutParams lp;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                if (!paused) startWatching();
            } else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                stopWatching();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        prefs = getSharedPreferences("onesec", MODE_PRIVATE);
        paused = prefs.getBoolean("paused", false);

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

        showBubble();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isInteractive() && !paused) startWatching();
    }

    // ---------------- Floating icon ----------------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showBubble() {
        if (bubble != null) return;
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            int size = dp(52);
            lp = new WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            int screenW = getResources().getDisplayMetrics().widthPixels;
            lp.x = prefs.getInt("x", screenW - size - dp(8));
            lp.y = prefs.getInt("y", dp(320));

            bubble = new Bubble(this);
            bubble.setActive(!paused);
            bubble.setOnClickListener(v -> togglePaused());
            bubble.setOnTouchListener(new View.OnTouchListener() {
                float downX, downY;
                int startX, startY;
                boolean moved;

                @Override
                public boolean onTouch(View v, MotionEvent ev) {
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = ev.getRawX();
                            downY = ev.getRawY();
                            startX = lp.x;
                            startY = lp.y;
                            moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = ev.getRawX() - downX;
                            float dy = ev.getRawY() - downY;
                            if (!moved && Math.hypot(dx, dy) > dp(8)) moved = true;
                            if (moved) {
                                lp.x = startX + (int) dx;
                                lp.y = startY + (int) dy;
                                try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) { }
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (moved) {
                                prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                            } else {
                                v.performClick();
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });
            wm.addView(bubble, lp);
        } catch (Exception e) {
            bubble = null; // never let the icon break the main feature
        }
    }

    private void hideBubble() {
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) { }
        }
        bubble = null;
    }

    private void togglePaused() {
        paused = !paused;
        prefs.edit().putBoolean("paused", paused).apply();
        if (bubble != null) bubble.setActive(!paused);
        if (paused) {
            stopWatching();
            Toast.makeText(this, "One Second Off paused", Toast.LENGTH_SHORT).show();
        } else {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isInteractive()) startWatching();
            Toast.makeText(this, "One Second Off is ON", Toast.LENGTH_SHORT).show();
        }
    }

    /** Round dark icon with a white phone outline and a dot: red = on, grey = paused. */
    private static class Bubble extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint phone = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private boolean active = true;

        Bubble(Context c) {
            super(c);
            bg.setColor(Color.argb(220, 38, 50, 56));
            phone.setColor(Color.WHITE);
            phone.setStyle(Paint.Style.STROKE);
            setClickable(true);
            updateDescription();
        }

        void setActive(boolean a) {
            active = a;
            updateDescription();
            invalidate();
        }

        private void updateDescription() {
            setContentDescription(active
                    ? "One Second Off is on. Tap to pause."
                    : "One Second Off is paused. Tap to turn on.");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            canvas.drawCircle(cx, cy, Math.min(w, h) / 2f, bg);

            float pw = w * 0.30f, ph = h * 0.52f;
            phone.setStrokeWidth(w * 0.05f);
            r.set(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f);
            canvas.drawRoundRect(r, w * 0.06f, w * 0.06f, phone);

            dot.setColor(active ? Color.rgb(229, 57, 53) : Color.rgb(144, 164, 174));
            canvas.drawCircle(cx, cy, w * 0.10f, dot);
        }
    }

    // ---------------- Motion detection ----------------

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
        hideBubble();
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
