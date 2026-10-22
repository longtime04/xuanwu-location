package com.muse.xuanwu;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/** 悬浮摇杆：推杆移动模拟位置（定点模式下生效）。全部静态嵌套类，避开本机 d8 缺陷。 */
public class JoystickService extends Service {
    public static volatile boolean shown = false;

    WindowManager wm;
    View root;
    View knob;
    float vx = 0, vy = 0;   // 归一化推杆量 -1..1
    Handler ui;
    MoverTask mover;

    static class MoverTask implements Runnable {
        private final JoystickService svc;
        MoverTask(JoystickService s) { svc = s; }
        @Override public void run() {
            double len = Math.hypot(svc.vx, svc.vy);
            if (len > 0.12 && MockService.running && MockService.mode == MockService.MODE_POINT) {
                double spd = 22.0 * Math.min(1.0, len);
                double ux = svc.vx / len, uy = svc.vy / len;
                double dE = ux * spd * 0.1;
                double dN = -uy * spd * 0.1;
                MockService.nudgeMeters(dN, dE);
                MockService.curSpeed = (float) spd;
                MockService.curBearing = (float) ((Math.toDegrees(Math.atan2(dE, dN)) + 360) % 360);
            }
            svc.ui.postDelayed(this, 100);
        }
    }

    static class PadTouch implements View.OnTouchListener {
        private final JoystickService svc;
        PadTouch(JoystickService s) { svc = s; }
        @Override public boolean onTouch(View v, MotionEvent e) {
            float cx = svc.root.getWidth() / 2f;
            float cy = svc.root.getHeight() - svc.dp(52);
            float r = svc.dp(34);
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                svc.vx = 0; svc.vy = 0;
                svc.knob.setTranslationX(0); svc.knob.setTranslationY(0);
                MockService.curSpeed = 0.01f;
                return true;
            }
            float dx = e.getX() - cx, dy = e.getY() - cy;
            double len = Math.hypot(dx, dy);
            if (len > r) { dx = (float) (dx / len * r); dy = (float) (dy / len * r); }
            svc.knob.setTranslationX(dx); svc.knob.setTranslationY(dy);
            svc.vx = dx / r; svc.vy = dy / r;
            return true;
        }
    }

    static class CloseClick implements View.OnClickListener {
        private final JoystickService svc;
        CloseClick(JoystickService s) { svc = s; }
        @Override public void onClick(View v) { svc.stopSelf(); }
    }

    int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (root != null) return START_STICKY;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        FrameLayout fl = new FrameLayout(this);
        root = fl;

        View pad = new View(this);
        GradientDrawable padBg = new GradientDrawable();
        padBg.setShape(GradientDrawable.OVAL);
        padBg.setColor(0xE6072019);
        padBg.setStroke(dp(2), 0xFFDEC684);
        pad.setBackground(padBg);
        FrameLayout.LayoutParams padLp = new FrameLayout.LayoutParams(dp(104), dp(104));
        padLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        fl.addView(pad, padLp);

        knob = new View(this);
        GradientDrawable knobBg = new GradientDrawable();
        knobBg.setShape(GradientDrawable.OVAL);
        knobBg.setColor(0xFF2DD4BF);
        knobBg.setStroke(dp(2), 0xFFEEF7F3);
        knob.setBackground(knobBg);
        FrameLayout.LayoutParams knobLp = new FrameLayout.LayoutParams(dp(42), dp(42));
        knobLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        knobLp.bottomMargin = dp(31);
        fl.addView(knob, knobLp);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(13);
        close.setTextColor(0xFFDEC684);
        close.setGravity(Gravity.CENTER);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setShape(GradientDrawable.OVAL);
        closeBg.setColor(0xE6072019);
        closeBg.setStroke(dp(1), 0xFFDEC684);
        close.setBackground(closeBg);
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(26), dp(26));
        closeLp.gravity = Gravity.TOP | Gravity.END;
        fl.addView(close, closeLp);
        close.setOnClickListener(new CloseClick(this));
        fl.setOnTouchListener(new PadTouch(this));

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(124), dp(140), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.x = dp(8);
        wm.addView(fl, lp);
        shown = true;

        ui = new Handler(Looper.getMainLooper());
        mover = new MoverTask(this);
        ui.post(mover);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        shown = false;
        if (ui != null && mover != null) ui.removeCallbacks(mover);
        if (wm != null && root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
        }
        root = null;
        MockService.curSpeed = 0.01f;
        super.onDestroy();
    }
}
