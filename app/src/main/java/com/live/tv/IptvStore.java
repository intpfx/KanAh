package com.live.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Persistence and loading for the IPTV source.
 *
 * <p>Precedence, deliberately simple to explain in the settings screen:
 * <ol>
 *   <li>a configured source url - fetched fresh, and the body is cached on disk;
 *   <li>when that fetch fails, the last cached copy (marked stale);
 *   <li>otherwise nothing: the channel list is exactly the CCTV pages from {@link Channels}.
 * </ol>
 *
 * <p>There is no bundled source any more. The television ships with the official CCTV pages and
 * nothing else; every extra channel comes from a source the user configured on purpose.
 *
 * <p>Everything lives in the app's private files dir, so no storage permission is ever needed.
 */
public final class IptvStore {
    private static final String PREFS = "iptv";
    private static final String KEY_SOURCE = "sourceUrl";
    private static final String KEY_LAST = "lastChannel";
    private static final String KEY_UPDATED = "updatedAt";
    private static final String KEY_IMPORTED = "imported";
    private static final String KEY_HIDDEN = "hidden";
    private static final String CACHE_FILE = "iptv-cache.m3u";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final String UA = "Live/0.4 (Android)";

    private IptvStore() {
    }

    /** Outcome of a load, including the line the settings screen shows. */
    public static final class Result {
        public final List<Channel> channels;
        /** Everything the source offered, including entries the user removed earlier. */
        public final List<Channel> all;
        public final String status;
        public final boolean stale;

        Result(List<Channel> channels, List<Channel> all, String status, boolean stale) {
            this.channels = channels;
            this.all = all;
            this.status = status;
            this.stale = stale;
        }
    }

    public interface Callback {
        void onLoaded(Result result);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String sourceUrl(Context context) {
        return prefs(context).getString(KEY_SOURCE, "");
    }

    public static void setSourceUrl(Context context, String url) {
        String clean = url == null ? "" : url.trim();
        // A url and a pasted playlist are alternatives; the newest wins.
        prefs(context).edit().putString(KEY_SOURCE, clean).putBoolean(KEY_IMPORTED, false).apply();
    }

    public static boolean imported(Context context) {
        return prefs(context).getBoolean(KEY_IMPORTED, false);
    }

    /** Replaces the playlist with pasted text (LAN page or a file); no network needed. */
    public static boolean importPlaylist(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (M3u.parse(text).isEmpty()) return false;
        try {
            writeCache(context, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        prefs(context).edit().putBoolean(KEY_IMPORTED, true).putString(KEY_SOURCE, "").apply();
        return true;
    }

    public static String lastChannel(Context context) {
        return prefs(context).getString(KEY_LAST, "");
    }

    public static void setLastChannel(Context context, String key) {
        prefs(context).edit().putString(KEY_LAST, key == null ? "" : key).apply();
    }

    public static long updatedAt(Context context) {
        return prefs(context).getLong(KEY_UPDATED, 0L);
    }

    /** Channels the user removed after a diagnosis; kept so a refresh cannot resurrect them. */
    public static java.util.Set<String> hidden(Context context) {
        return new java.util.HashSet<>(prefs(context)
                .getStringSet(KEY_HIDDEN, java.util.Collections.<String>emptySet()));
    }

    public static void hide(Context context, java.util.Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        java.util.Set<String> set = hidden(context);
        set.addAll(keys);
        prefs(context).edit().putStringSet(KEY_HIDDEN, set).apply();
    }

    /** Brings one channel back, used from the invalid list. */
    public static void unhide(Context context, String key) {
        if (key == null || key.isEmpty()) return;
        java.util.Set<String> set = hidden(context);
        if (set.remove(key)) prefs(context).edit().putStringSet(KEY_HIDDEN, set).apply();
    }

    public static void clearHidden(Context context) {
        prefs(context).edit().remove(KEY_HIDDEN).apply();
    }

    public static File cacheFile(Context context) {
        return new File(context.getFilesDir(), CACHE_FILE);
    }

    /** Replaces the cached playlist, used by the LAN config page as well. */
    public static void writeCache(Context context, byte[] body) throws IOException {
        try (FileOutputStream out = new FileOutputStream(cacheFile(context))) {
            out.write(body);
        }
        prefs(context).edit().putLong(KEY_UPDATED, System.currentTimeMillis()).apply();
    }

    /** Blocking; call from a worker thread or use {@link #loadAsync}. Applies the hidden set. */
    public static Result load(Context context) {
        Result raw = loadAll(context);
        java.util.Set<String> hidden = hidden(context);
        if (hidden.isEmpty()) return raw;
        List<Channel> kept = new java.util.ArrayList<>();
        for (Channel channel : raw.channels) {
            if (!hidden.contains(channel.key)) kept.add(channel);
        }
        int removed = raw.channels.size() - kept.size();
        String status = removed == 0 ? raw.status : raw.status + " · 已隐藏 " + removed + " 个";
        return new Result(kept, raw.all, status, raw.stale);
    }

    private static Result loadAll(Context context) {
        String url = sourceUrl(context);
        if (imported(context)) {
            Result imported = fromCache(context, "已导入的列表");
            if (imported != null) return imported;
        }
        if (!url.isEmpty()) {
            try {
                byte[] body = fetch(url);
                writeCache(context, body);
                List<Channel> channels = M3u.parseBytes(body);
                return new Result(channels, channels, "已从网络更新 · " + channels.size() + " 个频道", false);
            } catch (IOException e) {
                Result cached = fromCache(context, "网络失败，使用缓存");
                if (cached != null) {
                    return cached;
                }
                return new Result(Collections.<Channel>emptyList(), Collections.<Channel>emptyList(),
                        "网络失败且无缓存：" + shortReason(e), true);
            }
        }
        // No bundled list: without a configured source the television plays the CCTV pages only.
        return new Result(Collections.<Channel>emptyList(), Collections.<Channel>emptyList(), "未配置 IPTV 源", false);
    }

    public static void loadAsync(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final Result result = load(app);
            main.post(() -> callback.onLoaded(result));
        }, "iptv-load").start();
    }

    private static Result fromCache(Context context, String prefix) {
        File file = cacheFile(context);
        if (!file.isFile() || file.length() == 0) {
            return null;
        }
        try {
            byte[] body = readAll(new java.io.FileInputStream(file));
            List<Channel> channels = M3u.parseBytes(body);
            return new Result(channels, channels, prefix + " · " + channels.size() + " 个频道", true);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] fetch(String url) throws IOException {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IOException("仅支持 http/https");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept", "*/*");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            return readAll(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = stream.read(buffer)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("列表过大");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Parses playlist text, used by the LAN config page before it caches the body. */
    public static List<Channel> parseText(String text) {
        return M3u.parse(text);
    }

    private static String shortReason(IOException e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 40 ? message.substring(0, 40) : message;
    }
}
