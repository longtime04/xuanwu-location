package com.muse.xuanwu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.Random;

/** 前台服务：每秒向 GPS / 网络测试提供者注入目标位置（WGS-84）。 */
public class MockService extends Service {
    public static final String ACTION_START = "com.muse.xuanwu.START";
    public static final String ACTION_STOP = "com.muse.xuanwu.STOP";
    private static final String CH = "xuanwu_mock";
    static final String PREF = "xuanwu";

    public static volatile boolean running = false;
    public static volatile long startTimeMs = 0;
    public static volatile String targetName = "";
    public static volatile double targetLat = 0, targetLng = 0;
    public static volatile String lastError = "";
    public static volatile int pushCount = 0;
    public static volatile boolean verified = false;

    /* 当前实际注入点（摇杆/路线会移动它）与运动状态 */
    public static volatile double curLat = 0, curLng = 0;
    public static volatile float curBearing = 1.0f;
    public static volatile float curSpeed = 0.01f;
    /* 路线模式：沿 A→B 直线匀速移动 */
    public static final int MODE_POINT = 0, MODE_ROUTE = 1;
    public static volatile int mode = MODE_POINT;
    public static volatile double routeALat, routeALng, routeBLat, routeBLng;
    public static volatile double routeSpeedMps = 1.4, routeTraveledM = 0, routeTotalM = 0;
    public static volatile boolean routeDone = false;

    static double haversineM(double la1, double lo1, double la2, double lo2) {
        double r = 6371000.0, dLa = Math.toRadians(la2 - la1), dLo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLa / 2) * Math.sin(dLa / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    static double bearingDeg(double la1, double lo1, double la2, double lo2) {
        double dLo = Math.toRadians(lo2 - lo1);
        double y = Math.sin(dLo) * Math.cos(Math.toRadians(la2));
        double x = Math.cos(Math.toRadians(la1)) * Math.sin(Math.toRadians(la2))
                - Math.sin(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.cos(dLo);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** 摇杆：把当前点（及定点模式的目标）平移 */
    public static void nudgeMeters(double dNorth, double dEast) {
        if (mode != MODE_POINT) return;
        curLat += dNorth / 111320.0;
        curLng += dEast / (111320.0 * Math.cos(Math.toRadians(curLat)));
        targetLat = curLat;
        targetLng = curLng;
    }

    /** 路线模式每秒推进 */
    void advanceRoute() {
        if (mode != MODE_ROUTE || routeDone) return;
        routeTraveledM += routeSpeedMps;
        double f = routeTotalM <= 0 ? 1 : Math.min(1.0, routeTraveledM / routeTotalM);
        curLat = routeALat + (routeBLat - routeALat) * f;
        curLng = routeALng + (routeBLng - routeALng) * f;
        curBearing = (float) bearingDeg(routeALat, routeALng, routeBLat, routeBLng);
        curSpeed = (float) routeSpeedMps;
        if (f >= 1.0) { routeDone = true; curSpeed = 0.01f; }
    }

    LocationManager lm;
    Handler handler;
    HandlerThread tickThread;
    PowerManager.WakeLock wakeLock;
    final Random rnd = new Random();
    TickTask tick;
    int tickCount = 0;

    /** 静态嵌套（避开本机 d8 对非静态内部类的缺陷） */
    static class TickTask implements Runnable {
        private final MockService svc;
        TickTask(MockService s) { svc = s; }
        @Override public void run() {
            if (!running) return;
            svc.advanceRoute();
            for (String p : svc.activeProviders) svc.pushLocation(p);
            svc.tickCount++;
            if (svc.tickCount % 30 == 0) svc.reassertProviders();
            svc.handler.postDelayed(this, 1000);
        }
    }

    /** 通道自愈：系统有时会摘掉测试通道，每 30 秒重建一遍（静默，失败不打扰） */
    void reassertProviders() {
        if (lm == null) return;
        java.util.List<String> want = new java.util.ArrayList<>();
        want.add(LocationManager.GPS_PROVIDER);
        want.add(LocationManager.NETWORK_PROVIDER);
        want.add(LocationManager.FUSED_PROVIDER);
        for (String p : want) {
            if (addProvider(p) == null && !activeProviders.contains(p)) activeProviders.add(p);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(act)) {
            stopMock();
            stopSelf();
            return START_NOT_STICKY;
        }
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        if (intent != null && intent.hasExtra("route")) {
            mode = MODE_ROUTE;
            routeALat = intent.getDoubleExtra("aLat", 0);
            routeALng = intent.getDoubleExtra("aLng", 0);
            routeBLat = intent.getDoubleExtra("bLat", 0);
            routeBLng = intent.getDoubleExtra("bLng", 0);
            routeSpeedMps = intent.getDoubleExtra("speedMps", 1.4);
            routeTotalM = haversineM(routeALat, routeALng, routeBLat, routeBLng);
            routeTraveledM = 0;
            routeDone = false;
            curLat = routeALat;
            curLng = routeALng;
            targetLat = routeBLat;
            targetLng = routeBLng;
            curSpeed = (float) routeSpeedMps;
            targetName = intent.getStringExtra("name");
            if (targetName == null) targetName = "";
            sp.edit().putBoolean("should_run", true).apply();
        } else if (intent != null && intent.hasExtra("lat")) {
            mode = MODE_POINT;
            routeDone = false;
            targetLat = intent.getDoubleExtra("lat", 0);
            targetLng = intent.getDoubleExtra("lng", 0);
            curLat = targetLat;
            curLng = targetLng;
            curSpeed = 0.01f;
            curBearing = 1.0f;
            targetName = intent.getStringExtra("name");
            if (targetName == null) targetName = "";
            sp.edit().putFloat("lat", (float) targetLat).putFloat("lng", (float) targetLng)
                    .putString("name", targetName).putBoolean("should_run", true).apply();
        } else {
            targetLat = sp.getFloat("lat", 0);
            targetLng = sp.getFloat("lng", 0);
            targetName = sp.getString("name", "");
            if (curLat == 0 && curLng == 0) { curLat = targetLat; curLng = targetLng; }
        }
        startForeground(7, buildNotification());
        startMock();
        if (!running) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(new NotificationChannel(CH, "定位模拟", NotificationManager.IMPORTANCE_LOW));
            }
        }
        Intent stop = new Intent(this, MockService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setContentTitle("玄武定位正在模拟位置")
                .setContentText(targetName)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", pi)
                .setOngoing(true)
                .build();
    }

    /**
     * 建测试提供者；失败返回错误说明，成功返回 null。
     * 参数配方照开源项目 Fake Traveler（F-Droid 上久经考验）的成熟写法：
     * requiresSatellite / supportsAltitude 必须为 false（为 true 时安卓 12+ 底子会抛 IllegalArgumentException），
     * 功耗/精度随系统版本取 (0,5) 或 (1,2)，失败重试 3 次。
     */
    private String addProvider(String provider) {
        int powerUsage = 0, accuracy = 5;
        if (Build.VERSION.SDK_INT >= 30) { powerUsage = 1; accuracy = 2; }
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try {
                try { lm.removeTestProvider(provider); } catch (Exception ignored) { }
                lm.addTestProvider(provider, false, false, false, false, false, true, true, powerUsage, accuracy);
                lm.setTestProviderEnabled(provider, true);
                return null;
            } catch (SecurityException se) {
                return "系统拒绝：本 App 还没被选为「模拟位置信息应用」";
            } catch (Exception e) {
                last = e;
            }
        }
        return "建立模拟通道失败：" + (last == null ? "未知"
                : last.getClass().getSimpleName() + " " + String.valueOf(last.getMessage()));
    }

    /** 当前已建立的通道，TickTask 按此列表推送 */
    final java.util.List<String> activeProviders = new java.util.ArrayList<>();

    void pushLocation(String provider) {
        try {
            double jLat = curLat + (rnd.nextDouble() - 0.5) * 0.00004;
            double jLng = curLng + (rnd.nextDouble() - 0.5) * 0.00004;
            Location loc = new Location(provider);
            loc.setLatitude(jLat);
            loc.setLongitude(jLng);
            loc.setAltitude(3.0);
            loc.setAccuracy(3.0f);
            loc.setSpeed(curSpeed);
            loc.setBearing(curBearing);
            loc.setTime(System.currentTimeMillis());
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            if (Build.VERSION.SDK_INT >= 26) {
                loc.setBearingAccuracyDegrees(0.1f);
                loc.setVerticalAccuracyMeters(0.1f);
                loc.setSpeedAccuracyMetersPerSecond(0.01f);
            }
            lm.setTestProviderLocation(provider, loc);
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                pushCount++;
                if (pushCount % 3 == 0) verifyNow();
            }
        } catch (Exception e) {
            if (lastError.length() == 0) {
                lastError = "注入被拒：" + e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage());
            }
        }
    }

    /** 回读系统里的 GPS 位置，确认模拟真的被系统接受了 */
    private void verifyNow() {
        try {
            Location lk = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lk != null && Math.abs(lk.getLatitude() - curLat) < 0.001
                    && Math.abs(lk.getLongitude() - curLng) < 0.001) {
                verified = true;
            }
        } catch (Exception ignored) { }
    }

    private void startMock() {
        if (lm == null) lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        lastError = "";
        pushCount = 0;
        verified = false;
        activeProviders.clear();
        String gpsErr = addProvider(LocationManager.GPS_PROVIDER);
        if (gpsErr != null) {
            lastError = gpsErr;
            running = false;
            return;
        }
        activeProviders.add(LocationManager.GPS_PROVIDER);
        if (addProvider(LocationManager.NETWORK_PROVIDER) == null) {
            activeProviders.add(LocationManager.NETWORK_PROVIDER);
        }
        if (addProvider(LocationManager.FUSED_PROVIDER) == null) {
            activeProviders.add(LocationManager.FUSED_PROVIDER);
        }
        if (tickThread == null) {
            tickThread = new HandlerThread("xuanwu-tick");
            tickThread.start();
            handler = new Handler(tickThread.getLooper());
        }
        if (tick == null) tick = new TickTask(this);
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xuanwu:mock");
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
        if (!running) {
            running = true;
            if (startTimeMs == 0) startTimeMs = System.currentTimeMillis();
            handler.post(tick);
        }
    }

    private void stopMock() {
        running = false;
        startTimeMs = 0;
        mode = MODE_POINT;
        routeDone = false;
        curSpeed = 0.01f;
        curBearing = 1.0f;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (tickThread != null) { tickThread.quitSafely(); tickThread = null; handler = null; tick = null; }
        if (lm != null) {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER}) {
                try { lm.removeTestProvider(p); } catch (Exception ignored) { }
            }
        }
        activeProviders.clear();
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean("should_run", false).apply();
    }

    @Override
    public void onDestroy() {
        stopMock();
        super.onDestroy();
    }
}
