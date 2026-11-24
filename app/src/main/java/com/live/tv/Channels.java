package com.live.tv;
import java.net.URI;
public final class Channels {
    public static final String[] IDS = {"cctv1","cctv2","cctv3","cctv4","cctv5","cctv5plus","cctv6","cctv7","cctv8","cctvjilu","cctv10","cctv11","cctv12","cctv13","cctvchild","cctv15","cctv16","cctv17"};
    public static final String[] NAMES = {"CCTV-1 综合","CCTV-2 财经","CCTV-3 综艺","CCTV-4 中文国际（亚）","CCTV-5 体育","CCTV-5+ 体育赛事","CCTV-6 电影","CCTV-7 国防军事","CCTV-8 电视剧","CCTV-9 纪录","CCTV-10 科教","CCTV-11 戏曲","CCTV-12 社会与法","CCTV-13 新闻","CCTV-14 少儿","CCTV-15 音乐","CCTV-16 奥林匹克","CCTV-17 农业农村"};
    public static int wrap(int i) { return Math.floorMod(i, IDS.length); }
    public static String playerId(int i) {
        String id=IDS[wrap(i)];
        return id.equals("cctvjilu") ? "cctvjilu9" : id.equals("cctvchild") ? "cctvchild14" : id;
    }
    public static String url(int i) { return "https://tv.cctv.com/live/" + IDS[wrap(i)] + "/"; }
    public static boolean allowed(String value) {
        try {
            URI u = URI.create(value);
            if (!"https".equals(u.getScheme()) || !"tv.cctv.com".equals(u.getHost()) || u.getRawUserInfo()!=null || (u.getPort()!=-1 && u.getPort()!=443)) return false;
            for (String id: IDS) if (u.getPath().equals("/live/"+id+"/") || u.getPath().equals("/live/"+id+"/m/") || u.getPath().equals("/live/"+id+"/index.shtml")) return true;
        } catch (IllegalArgumentException ignored) {}
        return false;
    }
}
