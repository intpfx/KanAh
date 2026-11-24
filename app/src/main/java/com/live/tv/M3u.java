package com.live.tv;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal M3U/M3U8 playlist parser. No dependencies, no I/O policy of its own: callers decide
 * where the text comes from (asset, cache file or network).
 *
 * <p>Supported, because real Chinese IPTV lists use all of it:
 * <ul>
 *   <li>{@code #EXTINF:<duration> tvg-id=".." tvg-name=".." tvg-logo=".." group-title="..",Name}
 *   <li>{@code #EXTVLCOPT:http-user-agent=..} / {@code http-referrer=..} applied to the next url
 *   <li>{@code url|User-Agent=..&Referer=..} header suffix
 *   <li>{@code url1#url2} failover mirrors for one channel
 *   <li>CRLF, BOM, blank lines, comments and unknown {@code #} directives are tolerated
 * </ul>
 *
 * <p>Only http(s) and rtsp urls are kept; anything else is dropped so the UI never offers an
 * entry the platform player cannot open. Header names are whitelisted for the same reason.
 */
public final class M3u {

    private M3u() {
    }

    public static List<Channel> parse(String text) {
        try {
            return parse(new BufferedReader(new java.io.StringReader(text)));
        } catch (IOException impossible) {
            return Collections.emptyList();
        }
    }

    public static List<Channel> parse(InputStream in) throws IOException {
        return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    public static List<Channel> parse(Reader reader) throws IOException {
        return parse(reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader));
    }

    public static List<Channel> parse(BufferedReader in) throws IOException {
        List<Channel> channels = new ArrayList<>();
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> attrs = null;
        String display = null;
        String line;
        while ((line = in.readLine()) != null) {
            line = stripBom(line).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                int colon = line.indexOf(':');
                String rest = colon >= 0 ? line.substring(colon + 1) : "";
                int comma = firstUnquoted(rest, ',');
                attrs = parseAttributes(comma >= 0 ? rest.substring(0, comma) : rest);
                display = comma >= 0 ? rest.substring(comma + 1).trim() : "";
            } else if (line.startsWith("#EXTVLCOPT")) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                int eq = line.indexOf('=', colon);
                if (eq < 0) {
                    continue;
                }
                String name = headerName(line.substring(colon + 1, eq).trim());
                if (name != null && !headers.containsKey(name)) {
                    headers.put(name, line.substring(eq + 1).trim());
                }
            } else if (line.startsWith("#")) {
                continue; // #EXTM3U header, #KODIPROP, #EXTGRP and friends do not affect playback
            } else {
                List<String> urls = splitSources(line, headers);
                if (!urls.isEmpty()) {
                    channels.add(toChannel(attrs, display, urls, headers));
                }
                attrs = null;
                display = null;
                headers = new LinkedHashMap<>();
            }
        }
        return channels;
    }

    private static Channel toChannel(Map<String, String> attrs, String display, List<String> urls,
                                     Map<String, String> headers) {
        Map<String, String> a = attrs == null ? Collections.<String, String>emptyMap() : attrs;
        // A url line without any preceding #EXTINF is still usable; fall back to tvg-name, then url.
        String name = display != null && !display.isEmpty() ? display : a.get("tvg-name");
        String group = a.get("group-title");
        String logo = a.get("tvg-logo");
        String id = a.get("tvg-id");
        return Channel.stream(name, group, logo, id, urls,
                headers.isEmpty() ? Collections.<String, String>emptyMap() : new LinkedHashMap<>(headers));
    }

    /** Splits {@code url1#url2|User-Agent=x} into playable urls, collecting header suffixes. */
    private static List<String> splitSources(String token, Map<String, String> headers) {
        List<String> urls = new ArrayList<>();
        for (String part : token.split("#")) {
            String raw = part.trim();
            if (raw.isEmpty()) {
                continue;
            }
            int bar = raw.indexOf('|');
            String url = (bar >= 0 ? raw.substring(0, bar) : raw).trim();
            if (bar >= 0) {
                collectHeaders(raw.substring(bar + 1), headers);
            }
            if (playable(url) && !urls.contains(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    private static void collectHeaders(String spec, Map<String, String> headers) {
        for (String pair : spec.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = headerName(pair.substring(0, eq).trim());
            if (name == null || headers.containsKey(name)) {
                continue;
            }
            String value = pair.substring(eq + 1).trim();
            try {
                value = URLDecoder.decode(value, "UTF-8");
            } catch (RuntimeException | java.io.UnsupportedEncodingException ignored) {
                // Keep the raw value when it is not percent-encoded.
            }
            headers.put(name, value);
        }
    }

    /** Whitelist: a playlist must not be able to inject arbitrary headers. */
    private static String headerName(String raw) {
        String key = raw.toLowerCase(Locale.ROOT);
        if (key.startsWith("http-")) {
            key = key.substring(5);
        }
        if (key.equals("user-agent")) {
            return "User-Agent";
        }
        if (key.equals("referrer") || key.equals("referer")) {
            return "Referer";
        }
        if (key.equals("origin")) {
            return "Origin";
        }
        if (key.equals("cookie")) {
            return "Cookie";
        }
        return null;
    }

    private static boolean playable(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("rtsp://");
    }

    /** Parses {@code key="value"} pairs; quoted values may contain spaces and commas. */
    private static Map<String, String> parseAttributes(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            int eq = text.indexOf('=', i);
            if (eq < 0) {
                break;
            }
            int quote = eq + 1;
            if (quote >= n || text.charAt(quote) != '"') {
                i = eq + 1;
                continue;
            }
            int end = text.indexOf('"', quote + 1);
            if (end < 0) {
                break;
            }
            int start = eq - 1;
            while (start >= i && !Character.isWhitespace(text.charAt(start))) {
                start--;
            }
            String key = text.substring(start + 1, eq).trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) {
                out.put(key, text.substring(quote + 1, end));
            }
            i = end + 1;
        }
        return out;
    }

    /** Index of the first separator outside double quotes, or -1. */
    private static int firstUnquoted(String text, char separator) {
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == separator && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static String stripBom(String line) {
        return !line.isEmpty() && line.charAt(0) == '\uFEFF' ? line.substring(1) : line;
    }

    /** Exposed for tests: parses the same bytes the asset loader would hand over. */
    static List<Channel> parseBytes(byte[] bytes) throws IOException {
        return parse(new ByteArrayInputStream(bytes));
    }
}
