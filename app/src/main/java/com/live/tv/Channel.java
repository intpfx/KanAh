package com.live.tv;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One playable entry in the merged channel list.
 *
 * <p>Two kinds exist:
 * <ul>
 *   <li>{@link #WEB} - the built-in CCTV pages, played by the WebView pipeline. These keep their
 *       index into {@link Channels} so the existing navigation/probing code stays authoritative.
 *   <li>{@link #STREAM} - an imported IPTV url, played by {@link StreamPlayer}.
 * </ul>
 *
 * <p>{@code headers} exists because many IPTV servers reject requests that do not carry the
 * expected User-Agent/Referer; {@code urls} lists failover mirrors for the same channel.
 */
public final class Channel {
    public static final int WEB = 0;
    public static final int STREAM = 1;

    public final int kind;
    /** Index into {@link Channels} for WEB entries, -1 for STREAM. */
    public final int webIndex;
    public final String name;
    public final String group;
    public final String logo;
    /** Stable identity across restarts; also the persistence key. */
    public final String key;
    /** For STREAM: primary url first, then failover mirrors. For WEB: the single page url. */
    public final List<String> urls;
    public final Map<String, String> headers;

    private Channel(int kind, int webIndex, String name, String group, String logo, String key,
                    List<String> urls, Map<String, String> headers) {
        this.kind = kind;
        this.webIndex = webIndex;
        this.name = name;
        this.group = group;
        this.logo = logo;
        this.key = key;
        this.urls = Collections.unmodifiableList(urls);
        this.headers = Collections.unmodifiableMap(headers);
    }

    public static Channel web(int index) {
        String id = Channels.IDS[index];
        return new Channel(WEB, index, Channels.NAMES[index], "央视", null, "web:" + id,
                Collections.singletonList(Channels.url(index)), Collections.<String, String>emptyMap());
    }

    public static Channel stream(String name, String group, String logo, String id,
                                 List<String> urls, Map<String, String> headers) {
        String display = name == null || name.isEmpty() ? urls.get(0) : name;
        // Public lists often carry placeholder groups such as "Undefined"; show our own label then.
        String bucket = group == null || group.isEmpty() || group.equalsIgnoreCase("undefined")
                ? "IPTV" : group;
        String key = id != null && !id.isEmpty() ? "stream:" + id : "stream:" + urls.get(0);
        return new Channel(STREAM, -1, display, bucket, logo, key, urls, headers);
    }

    /** Primary url. */
    public String url() {
        return urls.get(0);
    }

    @Override public String toString() {
        return name;
    }
}
