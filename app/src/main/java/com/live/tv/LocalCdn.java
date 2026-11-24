package com.live.tv;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A very small http server on the loopback interface, there so the page can be ours.
 *
 * <p>The website's player is worth keeping - it carries its own MSE pipeline and its own mpeg-ts
 * remuxer, which is the part that would be a project to write. Everything around it on the page is not:
 * advertising scripts, analytics, a qr code library, social sharing, navigation, a dozen images, content
 * lists. The measurements say the player itself starts within the first seconds and the rest of the ten
 * is the website.
 *
 * <p>So this serves a page of our own at the same path shape the player expects, from assets it hands
 * over, with the stream proxied. The proxying is not a convenience: the cdn answers 404 without a
 * Referer of https://tv.cctv.com/, and a browser refuses to let a script set that header. Every request
 * for the stream therefore goes through here, where the header can be added.
 */
final class LocalCdn {

    private static final String TAG = "LOCALCDN";
    private static final String PLAYER_BASE = "https://js.player.cntv.cn/creator/";

    private final ServerSocket server;
    private final String referer;
    private final String userAgent;
    private final Map<String, byte[]> scripts = new ConcurrentHashMap<>();
    /**
     * Cookies for the cdn and the resolver, as a string per domain. The page is served from the loopback
     * address, which is a different origin, so the browser will not attach the cookies the player's own
     * authorization step needs - it waits for a fingerprint token that never arrives, and says nothing.
     * This server can attach them, because it is not a browser.
     */
    private final java.util.function.Supplier<String> cookies;
    private final Context context;
    /** The mp4 the page built, waiting to be served back as if it were arriving from a live stream. */
    private volatile byte[] uploaded;
    private volatile int uploads;
    private volatile int appendCount;
    /**
     * The growing mp4: the init segment goes in first, then a fragment each time one is ready, and the
     * response that carries it stays open. This is the piece that makes it a stream rather than a file.
     */
    private final java.io.ByteArrayOutputStream live = new java.io.ByteArrayOutputStream(4 * 1024 * 1024);
    private final Object liveLock = new Object();
    private volatile boolean running = true;

    LocalCdn(Context context, String referer, String userAgent,
             java.util.function.Supplier<String> cookies) throws IOException {
        this.context = context.getApplicationContext();
        this.referer = referer;
        this.userAgent = userAgent;
        this.cookies = cookies;
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(this::acceptLoop, "local-cdn");
        thread.setDaemon(true);
        thread.start();
        Log.d(TAG, "listening on 127.0.0.1:" + server.getLocalPort());
    }

    int port() {
        return server.getLocalPort();
    }

    String pageUrl(int channel) {
        return "http://127.0.0.1:" + port() + "/live/" + Channels.IDS[channel] + "/";
    }

    void stop() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = server.accept();
                Thread worker = new Thread(() -> handle(socket), "local-cdn-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept: " + e);
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(60000);
            // Read the head a byte at a time. A BufferedReader reads ahead, and the bytes it swallows
            // are the beginning of the request body - which is why a 986 KB upload never arrived.
            java.io.InputStream raw = s.getInputStream();
            StringBuilder head = new StringBuilder();
            int b;
            while ((b = raw.read()) >= 0) {
                head.append((char) b);
                int n = head.length();
                if (n >= 4 && head.charAt(n - 4) == '\r' && head.charAt(n - 3) == '\n'
                        && head.charAt(n - 2) == '\r' && head.charAt(n - 1) == '\n') {
                    break;
                }
            }
            String[] lines = head.toString().split("\r\n");
            if (lines.length == 0) return;
            String[] parts = lines[0].split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            int contentLength = 0;
            String range = null;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && lines[i].substring(0, colon).equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(lines[i].substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (colon > 0 && lines[i].substring(0, colon).equalsIgnoreCase("Range")) {
                    range = lines[i].substring(colon + 1).trim();
                }
            }
            if (path.startsWith("/upload")) {
                byte[] body = new byte[contentLength];
                java.io.DataInputStream data = new java.io.DataInputStream(raw);
                data.readFully(body);
                uploaded = body;
                uploads++;
                Log.d(TAG, "upload #" + uploads + ": " + body.length + " bytes");
                send(s, 200, "text/plain", "ok".getBytes("UTF-8"));
                return;
            }
            if (path.startsWith("/append")) {
                byte[] body = new byte[contentLength];
                java.io.DataInputStream data = new java.io.DataInputStream(raw);
                data.readFully(body);
                synchronized (liveLock) {
                    live.write(body);
                    liveLock.notifyAll();
                }
                appendCount++;
                Log.d(TAG, "append #" + appendCount + ": " + body.length + " bytes, total "
                        + live.size());
                send(s, 200, "text/plain", "ok".getBytes("UTF-8"));
                return;
            }
            if (path.startsWith("/reset")) {
                synchronized (liveLock) {
                    live.reset();
                    appendCount = 0;
                    liveLock.notifyAll();
                }
                Log.d(TAG, "stream reset");
                send(s, 200, "text/plain", "ok".getBytes("UTF-8"));
                return;
            }
            if (path.startsWith("/live.mp4")) {
                // Whatever has arrived, with a length, and ranges advertised. An open ended response is a
                // download that has not finished and the element waits for an end that never comes; a
                // ranged resource is one it will start playing from the beginning of, asking again as it
                // catches up with what has been written.
                byte[] snapshot;
                synchronized (liveLock) {
                    snapshot = live.toByteArray();
                }
                if (snapshot.length == 0) {
                    send(s, 404, "text/plain", new byte[0]);
                    return;
                }
                int from = 0;
                int to = snapshot.length - 1;
                if (range != null && range.startsWith("bytes=")) {
                    String spec = range.substring("bytes=".length());
                    int dash = spec.indexOf('-');
                    try {
                        if (dash > 0) {
                            from = Integer.parseInt(spec.substring(0, dash));
                            String tail = spec.substring(dash + 1);
                            if (!tail.isEmpty()) to = Math.min(to, Integer.parseInt(tail));
                        } else if (dash == 0) {
                            int last = Integer.parseInt(spec.substring(1));
                            from = Math.max(0, snapshot.length - last);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (from >= snapshot.length) {
                    send(s, 416, "text/plain", new byte[0]);
                    return;
                }
                int length = to - from + 1;
                java.io.OutputStream out = s.getOutputStream();
                String responseHead = "HTTP/1.1 " + (range != null ? "206 Partial Content" : "200 OK") + "\r\n"
                        + "Content-Type: video/mp4\r\n"
                        + "Accept-Ranges: bytes\r\n"
                        + "Content-Length: " + length + "\r\n"
                        + (range != null ? "Content-Range: bytes " + from + "-" + to + "/"
                            + snapshot.length + "\r\n" : "")
                        + "Connection: close\r\n\r\n";
                out.write(responseHead.getBytes("ISO-8859-1"));
                out.write(snapshot, from, length);
                out.flush();
                Log.d(TAG, "live served " + from + "-" + to + " of " + snapshot.length
                        + (range != null ? " (range)" : ""));
                return;
            }
            if (path.startsWith("/file.mp4")) {
                // The same bytes, complete and with a length: if this plays and the chunked one does not,
                // the question is only how a growing file has to be presented.
                if (uploaded == null) send(s, 404, "text/plain", new byte[0]);
                else send(s, 200, "video/mp4", uploaded);
                return;
            }
            if (path.startsWith("/stream.mp4")) {
                byte[] body = uploaded;
                if (body == null) {
                    send(s, 404, "text/plain", new byte[0]);
                    return;
                }
                // Half now, the rest after a pause, chunked and open ended: if the element starts
                // playing before the rest arrives then a growing fragmented mp4 is something it will
                // play, which is the whole question.
                java.io.OutputStream out = s.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\n"
                        + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                        .getBytes("ISO-8859-1"));
                int half = body.length / 2;
                writeChunk(out, body, 0, half);
                out.flush();
                Log.d(TAG, "sent first " + half + " bytes, holding the rest");
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException ignored) {
                }
                writeChunk(out, body, half, body.length - half);
                out.write("0\r\n\r\n".getBytes("ISO-8859-1"));
                out.flush();
                Log.d(TAG, "sent the rest " + (body.length - half) + " bytes");
                return;
            }
            if (path.startsWith("/player.html")) {
                // The player as the app uses it: full screen, no proof of concept scaffolding, and a state
                // object the app can read to know when there is a picture to show.
                String target = "";
                int query = path.indexOf("u=");
                if (query >= 0) target = URLDecoder.decode(path.substring(query + 2), "UTF-8");
                send(s, 200, "text/html; charset=utf-8", playerPage(target).getBytes("UTF-8"));
            } else if (path.startsWith("/poc.html")) {
                String target = "";
                int query = path.indexOf("u=");
                if (query >= 0) target = URLDecoder.decode(path.substring(query + 2), "UTF-8");
                send(s, 200, "text/html; charset=utf-8", pocPage(target).getBytes("UTF-8"));
            } else if (path.startsWith("/asset/")) {
                String name = path.substring("/asset/".length());
                byte[] body = null;
                try (InputStream asset = context.getAssets().open(name)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = asset.read(buffer)) > 0) out.write(buffer, 0, read);
                    body = out.toByteArray();
                } catch (IOException e) {
                    Log.w(TAG, "asset " + name + ": " + e);
                }
                if (body == null) send(s, 404, "text/plain", new byte[0]);
                else send(s, 200, "application/javascript", body);
            } else if (path.startsWith("/live/")) {
                send(s, 200, "text/html; charset=utf-8", page(path).getBytes("UTF-8"));
            } else if (path.startsWith("/cache/")) {
                String name = path.substring("/cache/".length());
                byte[] body = scripts.get(name);
                if (body == null) {
                    body = fetch(PLAYER_BASE + name);
                    if (body != null) scripts.put(name, body);
                }
                if (body == null) send(s, 404, "text/plain", new byte[0]);
                else send(s, 200, name.endsWith(".js") ? "application/javascript"
                        : "application/octet-stream", body);
            } else if (path.startsWith("/cdn/")) {
                String target = URLDecoder.decode(path.substring("/cdn/".length()), "UTF-8");
                byte[] body = fetch(target);
                if (body == null) {
                    send(s, 502, "text/plain", new byte[0]);
                } else {
                    String text = new String(body, "ISO-8859-1");
                    if (text.startsWith("#EXTM3U")) {
                        body = rewrite(text, target).getBytes("ISO-8859-1");
                        send(s, 200, "application/vnd.apple.mpegurl", body);
                    } else {
                        send(s, 200, target.endsWith(".ts") ? "video/mp2t" : "application/octet-stream", body);
                    }
                }
            } else {
                send(s, 404, "text/plain", new byte[0]);
            }
        } catch (Throwable t) {
            Log.w(TAG, "handle: " + t);
        }
    }

    /**
     * The page the player is given. Deliberately almost empty: a container, and the player's own scripts.
     * The channel is in the path, which is where the player reads it from on the real site, and it is also
     * passed as a global in case this build of the player prefers that.
     */
    private String page(String path) {
        String channel = path.replace("/live/", "").replace("/", "");
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;padding:0;background:#000;overflow:hidden}"
                + "#player{width:100vw;height:100vh}</style></head><body>"
                + "<div id=\"player\"></div>"
                // Errors are collected rather than hoped for: a player that refuses to start on a page of
                // ours says why, and console messages have not been reaching the app.
                + "<script>window.__err=[];window.addEventListener('error',function(e){"
                + "window.__err.push(String(e.message)+' @'+(e.filename||'')+':'+(e.lineno||0));});"
                + "window.addEventListener('unhandledrejection',function(e){"
                + "window.__err.push('rejection '+String(e.reason));});</script>"
                + "<script>window.channel=" + quote(channel) + ";"
                + "window.channelFilePath=" + quote(channel) + ";"
                + "window.videoCenterId=" + quote(channel) + ";"
                + "window.guid=" + quote(channel) + ";</script>"
                + proxyPatch()
                + "<script src=\"/cache/swfobject.js\"></script>"
                + "<script src=\"/cache/liveplayer.js\"></script>"
                + "</body></html>";
    }

    /**
     * Routes every absolute address the page asks for back through this server, so the cdn sees the
     * Referer it insists on. Browsers will not let a script set that header; a server can.
     */
    private String proxyPatch() {
        return "<script>(function(){var o=XMLHttpRequest.prototype.open;"
                + "XMLHttpRequest.prototype.open=function(m,u){try{if(typeof u==='string'"
                + "&&/^https?:\\/\\//.test(u)&&u.indexOf('127.0.0.1')<0)"
                + "{arguments[1]='/cdn/'+encodeURIComponent(u);}}catch(e){}return o.apply(this,arguments);};"
                + "if(window.fetch){var f=window.fetch;window.fetch=function(i,n){try{"
                + "if(typeof i==='string'&&/^https?:\\/\\//.test(i)&&i.indexOf('127.0.0.1')<0)"
                + "{i='/cdn/'+encodeURIComponent(i);}}catch(e){}return f.call(this,i,n);};}})();</script>";
    }

    private String playerPage(String url) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000}"
                + "video{position:fixed;inset:0;width:100%;height:100%;object-fit:contain;background:#000}</style></head>"
                + "<body><video id=\"video\" playsinline autoplay muted></video>"
                + proxyPatch()
                + "<script src=\"/asset/remux.js\"></script>"
                + "<script>__liveRun(" + quote(url) + ");</script></body></html>";
    }

    /** The proof of concept: one segment, repackaged by hand, into MSE. */
    private String pocPage(String target) {
        String url = target.isEmpty() ? "" : target;
        return "<!doctype html><html><head><meta charset=\"utf-8\"></head>"
                + "<body style=\"margin:0;background:#111;color:#eee;font:14px monospace\">"
                + "<video id=\"video\" style=\"width:100vw;height:88vh;background:#000\" muted autoplay></video>"
                + "<div id=\"status\">starting</div>"
                + proxyPatch()
                + "<script src=\"/asset/remux.js\"></script>"
                + "<script>"
                + "var q=new URLSearchParams(location.search);"
                + "(q.get('live')==='1'?__liveRun:(q.get('blob')==='1'?__pocBlob:__pocRun))("
                + quote(url) + ");</script>"
                + "</body></html>";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Points every address in a playlist back at this server, so the cdn sees the right Referer. */
    private String rewrite(String playlist, String baseUrl) {
        StringBuilder out = new StringBuilder(playlist.length() + 256);
        for (String line : playlist.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                out.append(rewriteAttributes(line, baseUrl));
            } else {
                out.append(proxied(resolve(baseUrl, trimmed)));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String rewriteAttributes(String line, String baseUrl) {
        int at = line.indexOf("URI=\"");
        if (at < 0) return line;
        int end = line.indexOf('"', at + 5);
        if (end < 0) return line;
        String value = line.substring(at + 5, end);
        return line.substring(0, at + 5) + proxied(resolve(baseUrl, value)) + line.substring(end);
    }

    /**
     * Absolute, including this server's port. A root relative path looked right and was not: the page
     * resolves playlist entries against the address the playlist came from, which is the cdn's, so
     * /cdn/... became https://<cdn>/cdn/... and the cdn answered 403.
     */
    private String proxied(String absoluteUrl) {
        try {
            return "http://127.0.0.1:" + port() + "/cdn/" + URLEncoder.encode(absoluteUrl, "UTF-8");
        } catch (Exception e) {
            return absoluteUrl;
        }
    }

    private String resolve(String baseUrl, String relative) {
        try {
            return new URL(new URL(baseUrl), relative).toString();
        } catch (Exception e) {
            return relative;
        }
    }

    private byte[] fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Referer", referer);
            connection.setRequestProperty("Origin", "https://tv.cctv.com");
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "*/*");
            String cookie = cookies == null ? null : cookies.get();
            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
                Log.d(TAG, "fetch with " + cookie.length() + " chars of cookie: " + url);
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                // Logged loudly: a playlist that comes back 403 turns into an empty body and then into a
                // run where nothing happens at all, which is an expensive way to learn a status code.
                Log.w(TAG, "fetch " + code + " " + url);
                return null;
            }
            Log.d(TAG, "fetch 200 " + url);
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                return out.toByteArray();
            }
        } catch (Throwable t) {
            Log.w(TAG, "fetch " + url + ": " + t);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void writeChunk(java.io.OutputStream out, byte[] data, int from, int length)
            throws IOException {
        out.write((Integer.toHexString(length) + "\r\n").getBytes("ISO-8859-1"));
        out.write(data, from, length);
        out.write("\r\n".getBytes("ISO-8859-1"));
    }

    private void send(Socket socket, int status, String contentType, byte[] body) throws IOException {
        OutputStream out = socket.getOutputStream();
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }
}
