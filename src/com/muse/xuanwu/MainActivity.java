package com.muse.xuanwu;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class MainActivity extends Activity {
    WebView web;
    static final String PREF = "xuanwu";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(this), "XW");
        setContentView(web);
        web.loadUrl("file:///android_asset/map.html");
        new Thread(new WarmTask()).start();
        handleIntent(getIntent());
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.postDelayed(new JsTask(this,
                "window.__refreshState&&window.__refreshState()"), 400);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** 从其他 App 分享进来的位置（待网页端初始化时来取） */
    volatile ShareParse.Hit pendingShared;

    void handleIntent(Intent it) {
        if (it == null || it.getAction() == null) return;
        String text = null, subj = null;
        if (Intent.ACTION_SEND.equals(it.getAction())) {
            text = it.getStringExtra(Intent.EXTRA_TEXT);
            subj = it.getStringExtra(Intent.EXTRA_SUBJECT);
        } else if (Intent.ACTION_VIEW.equals(it.getAction())) {
            if (it.getData() != null) text = String.valueOf(it.getData());
        } else {
            return;
        }
        importText(text, subj);
    }

    void importText(String text, String subj) {
        ShareParse.Hit h = ShareParse.parse(text, subj);
        if (h == null) {
            runOnUiThread(new ToastTask(this, "没从分享内容里读出坐标"));
            return;
        }
        if (h.needNet) {
            runOnUiThread(new ToastTask(this, "正在解析分享链接…"));
            new Thread(new ResolveTask(this, h)).start();
            return;
        }
        deliverShared(h);
    }

    void deliverShared(ShareParse.Hit h) {
        pendingShared = h;
        js("window.__onShared&&window.__onShared(" + h.wgsLat + "," + h.wgsLng + ","
                + JSONObject.quote(h.name == null ? "" : h.name) + ")");
    }

    /** 展开短链（高德 surl 等），从最终地址或页面正文里找坐标 */
    static class ResolveTask implements Runnable {
        private final MainActivity act;
        private final ShareParse.Hit src;
        ResolveTask(MainActivity a, ShareParse.Hit h) { act = a; src = h; }
        @Override public void run() {
            ShareParse.Hit out = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(src.url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
                c.getResponseCode();
                String finalUrl = String.valueOf(c.getURL());
                out = ShareParse.parse(finalUrl, src.name);
                if (out == null || out.needNet) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null && sb.length() < 200000) sb.append(line);
                    r.close();
                    out = ShareParse.parse(sb.toString(), src.name);
                    if (out != null && out.needNet) out = null;
                }
                c.disconnect();
            } catch (Exception ignored) { }
            if (out != null && !out.needNet) {
                if (out.name == null || out.name.length() == 0) out.name = src.name;
                act.deliverShared(out);
            } else {
                act.runOnUiThread(new ToastTask(act, "没能从链接里读出坐标，可用「坐标」按钮手动输入"));
            }
        }
    }

    void js(final String code) {
        runOnUiThread(new JsTask(this, code));
    }

    /* ============ 静态嵌套小任务（避开本机 d8 对非静态内部类的缺陷） ============ */

    static class JsTask implements Runnable {
        private final MainActivity act;
        private final String code;
        JsTask(MainActivity a, String c) { act = a; code = c; }
        @Override public void run() {
            if (act.web != null) act.web.evaluateJavascript(code, null);
        }
    }

    static class ToastTask implements Runnable {
        private final Context ctx;
        private final String msg;
        ToastTask(Context c, String m) { ctx = c; msg = m; }
        @Override public void run() {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        }
    }

    static class SearchTask implements Runnable {
        private final MainActivity act;
        private final String q;
        private final int seq;
        SearchTask(MainActivity a, String q, int s) { act = a; this.q = q; seq = s; }

        static String httpGet(String u) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "XuanwuLocation/1.3 (personal tool)");
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        }

        /** 高德返回的字段有时是字符串、有时是空数组 []，统一取字符串 */
        static String s(org.json.JSONObject o, String k) {
            Object v = o.opt(k);
            return (v instanceof String) ? (String) v : "";
        }

        static org.json.JSONObject item(String display, String locGcj) throws Exception {
            String[] parts = locGcj.split(",");
            double[] w = Coord.gcj2wgs(Double.parseDouble(parts[1]), Double.parseDouble(parts[0]));
            org.json.JSONObject it = new org.json.JSONObject();
            it.put("display_name", display);
            it.put("lat", String.valueOf(w[0]));
            it.put("lon", String.valueOf(w[1]));
            return it;
        }

        /** 高德国内接口：先行政区定位（"北京"→北京市中心），再地点搜索 */
        String amapSearch() {
            try {
                org.json.JSONArray out = new org.json.JSONArray();
                String enc = URLEncoder.encode(q, "UTF-8");
                String g = httpGet("https://restapi.amap.com/v3/geocode/geo?address=" + enc + "&key=" + AmapKey.KEY);
                org.json.JSONObject gj = new org.json.JSONObject(g);
                if ("1".equals(gj.optString("status"))) {
                    org.json.JSONArray geos = gj.optJSONArray("geocodes");
                    if (geos != null && geos.length() > 0) {
                        org.json.JSONObject g0 = geos.getJSONObject(0);
                        String loc = s(g0, "location"), fa = s(g0, "formatted_address");
                        if (loc.length() > 0 && fa.length() > 0) out.put(item(fa, loc));
                    }
                }
                String p = httpGet("https://restapi.amap.com/v3/place/text?keywords=" + enc
                        + "&offset=10&page=1&extensions=base&key=" + AmapKey.KEY);
                org.json.JSONObject pj = new org.json.JSONObject(p);
                if ("1".equals(pj.optString("status"))) {
                    org.json.JSONArray pois = pj.optJSONArray("pois");
                    if (pois != null) {
                        for (int i = 0; i < pois.length() && out.length() < 9; i++) {
                            org.json.JSONObject poi = pois.getJSONObject(i);
                            String loc = s(poi, "location"), name = s(poi, "name");
                            if (loc.length() == 0 || name.length() == 0) continue;
                            String disp = name + ", " + s(poi, "pname") + s(poi, "cityname")
                                    + s(poi, "adname") + " " + s(poi, "address");
                            out.put(item(disp, loc));
                        }
                    }
                }
                return out.length() > 0 ? out.toString() : null;
            } catch (Exception e) {
                return null;
            }
        }

        String nominatimSearch() {
            try {
                String u = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&countrycodes=cn&q="
                        + URLEncoder.encode(q, "UTF-8");
                return httpGet(u);
            } catch (Exception e) {
                return "[]";
            }
        }

        @Override public void run() {
            String result = amapSearch();
            if (result == null) result = nominatimSearch();
            act.js("window.__onSearch&&window.__onSearch(" + seq + "," + JSONObject.quote(result) + ")");
        }
    }

    /** 冷启动预热：先跟高德服务器握个手，第一次搜索就不必等建连 */
    static class WarmTask implements Runnable {
        @Override public void run() {
            try { SearchTask.httpGet("https://restapi.amap.com/v3/geocode/geo?address=%E5%8C%97%E4%BA%AC&key=" + AmapKey.KEY); }
            catch (Exception ignored) { }
        }
    }

    static class OnceLocListener implements LocationListener {
        private final MainActivity act;
        OnceLocListener(MainActivity a) { act = a; }
        @Override public void onLocationChanged(Location l) {
            act.js("window.__onRealLoc&&window.__onRealLoc(" + l.getLatitude() + "," + l.getLongitude() + ")");
        }
        @Override public void onStatusChanged(String p, int st, Bundle e) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    }

    /* ============ JS 桥 ============ */

    public static class Bridge {
        private final MainActivity act;
        Bridge(MainActivity a) { act = a; }

        @JavascriptInterface
        public boolean isMockSelected() {
            try {
                AppOpsManager ao = (AppOpsManager) act.getSystemService(Context.APP_OPS_SERVICE);
                int mode = ao.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                        android.os.Process.myUid(), act.getPackageName());
                return mode == AppOpsManager.MODE_ALLOWED;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean isBatteryUnrestricted() {
            try {
                if (Build.VERSION.SDK_INT < 23) return true;
                android.os.PowerManager pm = (android.os.PowerManager) act.getSystemService(Context.POWER_SERVICE);
                return pm.isIgnoringBatteryOptimizations(act.getPackageName());
            } catch (Exception e) {
                return true;
            }
        }

        @JavascriptInterface
        public void openBatterySettings() {
            // 华为：优先跳自启动/应用管理页；不行再退到系统电池优化页、应用详情页
            try {
                Intent hw = new Intent();
                hw.setClassName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity");
                act.startActivity(hw);
                return;
            } catch (Exception ignored) { }
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(android.net.Uri.parse("package:" + act.getPackageName()));
                act.startActivity(i);
                return;
            } catch (Exception ignored) { }
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(android.net.Uri.parse("package:" + act.getPackageName()));
                act.startActivity(i);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void openDevSettings() {
            try {
                act.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Exception e) {
                try { act.startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
            }
        }

        @JavascriptInterface
        public void startMock(double lat, double lng, String name) {
            Intent i = new Intent(act, MockService.class)
                    .setAction(MockService.ACTION_START)
                    .putExtra("lat", lat).putExtra("lng", lng).putExtra("name", name);
            if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i); else act.startService(i);
            act.js("setTimeout(function(){window.__refreshState&&window.__refreshState()},600)");
        }

        @JavascriptInterface
        public void stopMock() {
            Intent i = new Intent(act, MockService.class).setAction(MockService.ACTION_STOP);
            act.startService(i);
            act.js("setTimeout(function(){window.__refreshState&&window.__refreshState()},400)");
        }

        @JavascriptInterface
        public String getState() {
            try {
                JSONObject o = new JSONObject();
                o.put("running", MockService.running);
                o.put("name", MockService.targetName);
                o.put("lat", MockService.targetLat);
                o.put("lng", MockService.targetLng);
                o.put("startTimeMs", MockService.startTimeMs);
                o.put("mockSelected", isMockSelected());
                o.put("lastError", MockService.lastError);
                o.put("pushCount", MockService.pushCount);
                o.put("verified", MockService.verified);
                o.put("battOk", isBatteryUnrestricted());
                o.put("curLat", MockService.curLat);
                o.put("curLng", MockService.curLng);
                o.put("mode", MockService.mode);
                o.put("routeDone", MockService.routeDone);
                o.put("routeProgress", MockService.routeTotalM > 0
                        ? MockService.routeTraveledM / MockService.routeTotalM : 0);
                o.put("joyShown", JoystickService.shown);
                return o.toString();
            } catch (Exception e) {
                return "{\"running\":false}";
            }
        }

        @JavascriptInterface
        public String getFavorites() {
            return act.getSharedPreferences(PREF, MODE_PRIVATE).getString("favorites", "[]");
        }

        @JavascriptInterface
        public void saveFavorites(String json) {
            act.getSharedPreferences(PREF, MODE_PRIVATE).edit().putString("favorites", json).apply();
        }

        @JavascriptInterface
        public void toast(String msg) {
            act.runOnUiThread(new ToastTask(act, msg));
        }

        @JavascriptInterface
        public void search(String q, int seq) {
            new Thread(new SearchTask(act, q, seq)).start();
        }

        @JavascriptInterface
        public void locateMe() {
            try {
                if (Build.VERSION.SDK_INT >= 23
                        && act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    toast("先允许定位权限");
                    return;
                }
                LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
                Location best = null;
                for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    try {
                        Location l = lm.getLastKnownLocation(p);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    } catch (Exception ignored) { }
                }
                if (best != null) {
                    act.js("window.__onRealLoc&&window.__onRealLoc(" + best.getLatitude() + "," + best.getLongitude() + ")");
                }
                OnceLocListener once = new OnceLocListener(act);
                try { lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, once, act.getMainLooper()); }
                catch (Exception e) {
                    try { lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, once, act.getMainLooper()); }
                    catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void openUrl(String url) {
            try {
                act.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void startRoute(double aLat, double aLng, double bLat, double bLng, double speedKmh, String name) {
            Intent i = new Intent(act, MockService.class)
                    .setAction(MockService.ACTION_START)
                    .putExtra("route", true)
                    .putExtra("aLat", aLat).putExtra("aLng", aLng)
                    .putExtra("bLat", bLat).putExtra("bLng", bLng)
                    .putExtra("speedMps", speedKmh / 3.6)
                    .putExtra("name", name);
            if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i); else act.startService(i);
            act.js("setTimeout(function(){window.__refreshState&&window.__refreshState()},600)");
        }

        @JavascriptInterface
        public boolean hasOverlay() {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(act);
        }

        @JavascriptInterface
        public void openOverlaySettings() {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                i.setData(android.net.Uri.parse("package:" + act.getPackageName()));
                act.startActivity(i);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void showJoystick() {
            try { act.startService(new Intent(act, JoystickService.class)); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void hideJoystick() {
            try { act.stopService(new Intent(act, JoystickService.class)); } catch (Exception ignored) { }
        }

        /** 网页初始化时来取冷启动分享进来的位置（取走即清空） */
        @JavascriptInterface
        public String getShared() {
            ShareParse.Hit h = act.pendingShared;
            if (h == null) return "";
            act.pendingShared = null;
            try {
                JSONObject o = new JSONObject();
                o.put("lat", h.wgsLat);
                o.put("lng", h.wgsLng);
                o.put("name", h.name == null ? "" : h.name);
                return o.toString();
            } catch (Exception e) {
                return "";
            }
        }

        /** 坐标面板里粘贴分享内容/链接时，交给原生解析 */
        @JavascriptInterface
        public void importText(String text) {
            act.importText(text, null);
        }
    }
}
