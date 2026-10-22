package com.muse.xuanwu;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析其他 App 分享进来的位置文本/链接，统一转成 WGS-84。
 * 支持：geo: 链接、高德 position=、谷歌 @/q=、百度 location=（BD-09）、
 * 纯坐标文本（自动判经纬顺序，国内默认按 GCJ-02 校正）；认不出坐标但带链接时标记 needNet 走网络展开。
 */
public class ShareParse {
    public static class Hit {
        public double wgsLat, wgsLng;
        public String name = "";
        public boolean needNet = false;
        public String url = "";
    }

    private static final Pattern GEO = Pattern.compile("geo:(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern POS = Pattern.compile("position=(\\d+\\.\\d+),(\\d+\\.\\d+)");
    private static final Pattern AT = Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern QP = Pattern.compile("[?&]q=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern LOC = Pattern.compile("location=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern PAIR = Pattern.compile("(-?\\d{1,3}\\.\\d{3,})\\s*[,，、]\\s*(-?\\d{1,3}\\.\\d{3,})");
    private static final Pattern URLP = Pattern.compile("https?://\\S+");
    private static final Pattern NAMEP = Pattern.compile("(?:name|title)=([^&\\s]+)");

    private static double D(Matcher m, int i) { return Double.parseDouble(m.group(i)); }

    public static Hit parse(String text, String subject) {
        if (text == null) return null;
        text = text.trim();
        if (text.length() == 0) return null;
        Hit h = new Hit();
        if (subject != null && subject.trim().length() > 0) h.name = subject.trim();
        Matcher nm = NAMEP.matcher(text);
        if (nm.find()) {
            try { h.name = URLDecoder.decode(nm.group(1), "UTF-8"); } catch (UnsupportedEncodingException ignored) { }
        }
        if (h.name.length() == 0 && !text.startsWith("http")) {
            String first = text.split("\n")[0].trim();
            if (first.length() >= 2 && first.length() <= 28 && !first.matches(".*\\d{2,}.*")) h.name = first;
        }
        Matcher m;
        m = GEO.matcher(text);
        if (m.find()) { h.wgsLat = D(m, 1); h.wgsLng = D(m, 2); return h; }
        boolean baidu = text.contains("baidu");
        m = POS.matcher(text);
        if (m.find()) { double[] w = Coord.gcj2wgs(D(m, 2), D(m, 1)); h.wgsLat = w[0]; h.wgsLng = w[1]; return h; }
        m = AT.matcher(text);
        if (m.find()) { h.wgsLat = D(m, 1); h.wgsLng = D(m, 2); return h; }
        m = QP.matcher(text);
        if (m.find()) { h.wgsLat = D(m, 1); h.wgsLng = D(m, 2); return h; }
        m = LOC.matcher(text);
        if (m.find()) {
            double la = D(m, 1), lo = D(m, 2);
            if (baidu) {
                double[] g = Coord.bd2gcj(la, lo);
                double[] w = Coord.gcj2wgs(g[0], g[1]);
                h.wgsLat = w[0]; h.wgsLng = w[1];
            } else {
                double[] w = Coord.gcj2wgs(la, lo);
                h.wgsLat = w[0]; h.wgsLng = w[1];
            }
            return h;
        }
        m = PAIR.matcher(text);
        while (m.find()) {
            double a = D(m, 1), b = D(m, 2);
            if (a >= 70 && a <= 140 && b >= 3 && b <= 55) {
                double[] w = Coord.gcj2wgs(b, a); h.wgsLat = w[0]; h.wgsLng = w[1]; return h;
            }
            if (a >= 3 && a <= 55 && b >= 70 && b <= 140) {
                double[] w = Coord.gcj2wgs(a, b); h.wgsLat = w[0]; h.wgsLng = w[1]; return h;
            }
            if (a >= -90 && a <= 90 && b >= -180 && b <= 180) { h.wgsLat = a; h.wgsLng = b; return h; }
        }
        m = URLP.matcher(text);
        if (m.find()) { h.needNet = true; h.url = m.group(0); return h; }
        return null;
    }
}
