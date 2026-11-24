import com.live.tv.Channel;
import com.live.tv.M3u;

import java.util.List;

/** Standalone M3U parser checks: javac M3u.java Channel.java Channels.java M3uTest.java. */
public final class M3uTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        basic();
        awkwardNames();
        vlcoOptsScope();
        urlHeaderSuffix();
        failoverMirrors();
        rejectsUnplayable();
        toleratesNoise();
        webChannelModel();
        System.out.println("M3U parsing, header scope, mirror failover and channel model tests passed.");
    }

    private static void basic() {
        List<Channel> list = M3u.parse(
                "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"cctv1\" tvg-name=\"CCTV1\" tvg-logo=\"http://l/1.png\" group-title=\"央视\",CCTV-1 综合\n"
                + "https://example.com/cctv1.m3u8\n"
                + "#EXTINF:-1 group-title=\"卫视\",湖南卫视\n"
                + "https://example.com/hunan.m3u8\n");
        check(list.size() == 2, "two entries expected, got " + list.size());
        Channel first = list.get(0);
        check(first.kind == Channel.STREAM, "imported entries are STREAM");
        check(first.webIndex == -1, "stream entries carry no web index");
        check("CCTV-1 综合".equals(first.name), "display name wins: " + first.name);
        check("央视".equals(first.group), "group-title parsed: " + first.group);
        check("http://l/1.png".equals(first.logo), "tvg-logo parsed");
        check("stream:cctv1".equals(first.key), "tvg-id becomes the persistence key: " + first.key);
        check("https://example.com/cctv1.m3u8".equals(first.url()), "url parsed");
        check("湖南卫视".equals(list.get(1).name), "second entry parsed");
        check("stream:https://example.com/hunan.m3u8".equals(list.get(1).key),
                "without tvg-id the url is the key");
    }

    /** A quoted attribute may contain commas; only the separator after the quotes ends the header. */
    private static void awkwardNames() {
        List<Channel> list = M3u.parse(
                "#EXTINF:-1 group-title=\"News, World\" tvg-name=\"Fallback\",Real, Name\n"
                + "https://example.com/a.m3u8\n"
                + "#EXTINF:0 tvg-name=\"Only TvgName\"\n"
                + "https://example.com/b.m3u8\n"
                + "#EXTINF:-1\n"
                + "https://example.com/c.m3u8\n");
        check("Real, Name".equals(list.get(0).name), "comma inside the name survives: " + list.get(0).name);
        check("News, World".equals(list.get(0).group), "comma inside a quoted attribute survives");
        check("Only TvgName".equals(list.get(1).name), "tvg-name is the fallback");
        check("https://example.com/c.m3u8".equals(list.get(2).name), "url is the last resort name");
    }

    /** #EXTVLCOPT only applies to the url line that follows it. */
    private static void vlcoOptsScope() {
        List<Channel> list = M3u.parse(
                "#EXTINF:-1,A\n"
                + "#EXTVLCOPT:http-user-agent=okhttp/3.12\n"
                + "#EXTVLCOPT:http-referrer=https://ref.example/\n"
                + "https://example.com/a.m3u8\n"
                + "#EXTINF:-1,B\n"
                + "https://example.com/b.m3u8\n");
        Channel a = list.get(0);
        Channel b = list.get(1);
        check("okhttp/3.12".equals(a.headers.get("User-Agent")), "user-agent attached to the next url");
        check("https://ref.example/".equals(a.headers.get("Referer")), "http-referrer mapped to Referer");
        check(b.headers.isEmpty(), "the following channel must not inherit headers");
    }

    private static void urlHeaderSuffix() {
        List<Channel> list = M3u.parse(
                "#EXTINF:-1,C\n"
                + "https://example.com/c.m3u8|User-Agent=Mozilla%2F5.0&Referer=https%3A%2F%2Fref.example%2F&Authorization=nope\n");
        Channel c = list.get(0);
        check("https://example.com/c.m3u8".equals(c.url()), "the suffix is stripped from the url: " + c.url());
        check("Mozilla/5.0".equals(c.headers.get("User-Agent")), "percent-decoded user-agent");
        check("https://ref.example/".equals(c.headers.get("Referer")), "percent-decoded referer");
        check(!c.headers.containsKey("Authorization"), "non-whitelisted headers are dropped");
    }

    private static void failoverMirrors() {
        List<Channel> list = M3u.parse(
                "#EXTINF:-1,D\n"
                + "https://example.com/primary.m3u8#https://example.com/backup.m3u8#https://example.com/primary.m3u8\n");
        Channel d = list.get(0);
        check(d.urls.size() == 2, "mirrors deduplicate, got " + d.urls.size());
        check("https://example.com/primary.m3u8".equals(d.url()), "primary url stays first");
        check("https://example.com/backup.m3u8".equals(d.urls.get(1)), "backup url retained");
    }

    private static void rejectsUnplayable() {
        List<Channel> list = M3u.parse(
                "#EXTINF:-1,Ignored\n"
                + "plugin://some.video.plugin/?url=abc\n"
                + "#EXTINF:-1,Also ignored\n"
                + "magnet:?xt=urn:btih:deadbeef\n"
                + "#EXTINF:-1,Kept\n"
                + "rtsp://example.com/live\n");
        check(list.size() == 1, "only the rtsp entry survives, got " + list.size());
        check("rtsp://example.com/live".equals(list.get(0).url()), "rtsp is playable");
    }

    private static void toleratesNoise() {
        List<Channel> list = M3u.parse(
                "\uFEFF#EXTM3U x-tvg-url=\"http://epg.example/e.xml\"\r\n"
                + "\r\n"
                + "# a comment\r\n"
                + "#KODIPROP:inputstream.adaptive.license_type=clearkey\r\n"
                + "#EXTINF:-1 group-title=\"高清\",Noisy Channel\r\n"
                + "  https://example.com/noisy.m3u8  \r\n"
                + "https://example.com/orphan.m3u8\r\n");
        check(list.size() == 2, "both urls are kept, got " + list.size());
        check("Noisy Channel".equals(list.get(0).name), "BOM/CRLF/blank lines/comments tolerated");
        check("https://example.com/noisy.m3u8".equals(list.get(0).url()), "surrounding spaces trimmed");
        check("https://example.com/orphan.m3u8".equals(list.get(1).url()),
                "a url without #EXTINF is still kept");
    }

    private static void webChannelModel() {
        List<Channel> list = M3u.parse("#EXTINF:-1,Anything\nhttps://example.com/x.m3u8\n");
        Channel stream = list.get(0);
        Channel web = Channel.web(13);
        check(stream.kind != web.kind, "kinds differ so the activity can pick a backend");
        check(web.webIndex == 13, "web entries carry their Channels index");
        check("web:cctv13".equals(web.key), "web key is stable: " + web.key);
        check(web.url().startsWith("https://tv.cctv.com/live/cctv13"), "web url built from Channels");
        check(web.headers.isEmpty(), "web entries need no headers");
    }
}
