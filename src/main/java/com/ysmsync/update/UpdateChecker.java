package com.ysmsync.update;

import com.ysmsync.YSMPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * 检查 YSMSync 是否有新版本。
 * 通过 GitHub Releases API 获取最新版本号并进行语义化版本比较。
 */
public class UpdateChecker {

    private static final String GITHUB_API = "https://api.github.com/repos/SSChen09/YSMSync/releases/latest";

    private final YSMPlugin plugin;
    private volatile String latestVersion;
    private final AtomicBoolean checking = new AtomicBoolean(false);

    public UpdateChecker(YSMPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 异步检查更新。
     */
    public CompletableFuture<UpdateResult> check() {
        if (!checking.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(
                    new UpdateResult(false, null, null, "Already checking"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(GITHUB_API).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int code = conn.getResponseCode();
                if (code != 200) {
                    return new UpdateResult(false, null, null, "HTTP " + code);
                }

                String body;
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[1024];
                    int len;
                    while ((len = reader.read(buf)) != -1) {
                        sb.append(buf, 0, len);
                    }
                    body = sb.toString();
                }

                String tagName = extractTag(body);
                if (tagName == null) {
                    return new UpdateResult(false, null, null, "Failed to parse response");
                }

                latestVersion = tagName;
                String downloadUrl = extractDownloadUrl(body);
                String current = plugin.getDescription().getVersion();
                boolean hasUpdate = isNewer(current, tagName);

                return new UpdateResult(hasUpdate, tagName, downloadUrl, null);
            } catch (Exception e) {
                return new UpdateResult(false, null, null, e.getMessage());
            } finally {
                checking.set(false);
            }
        });
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    private static final String GITHUB_PROXY = "https://gh-proxy.org";

    /**
     * 下载 JAR 文件到指定路径。
     * 先尝试直连 GitHub，超时或失败后自动通过 gh-proxy.org 加速下载。
     *
     * @param downloadUrl GitHub Release 资源下载地址
     * @param target 目标文件路径
     * @return 下载是否成功
     */
    public boolean download(String downloadUrl, File target) {
        // 确保父目录存在
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        // 直连 GitHub
        plugin.logDebug("Downloading from GitHub: " + downloadUrl);
        if (tryDownload(downloadUrl, target)) {
            return true;
        }

        // 回退到 gh-proxy.org 加速
        String proxyUrl = GITHUB_PROXY + "/" + downloadUrl;
        plugin.getLogger().info("GitHub download failed, trying proxy: " + GITHUB_PROXY);
        return tryDownload(proxyUrl, target);
    }

    /**
     * 尝试从指定 URL 下载文件。
     */
    private boolean tryDownload(String url, File target) {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url);

            int code = conn.getResponseCode();
            if (code != 200) {
                plugin.getLogger().warning("Download failed: HTTP " + code + " (" + url + ")");
                return false;
            }

            long totalSize = conn.getContentLengthLong();

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    downloaded += len;
                    if (totalSize > 0) {
                        int progress = (int) (downloaded * 100 / totalSize);
                        if (progress % 10 == 0) {
                            plugin.logDebug("Download progress: " + progress + "%");
                        }
                    }
                }
            }

            // 校验文件大小
            if (totalSize > 0 && tmp.length() != totalSize) {
                plugin.getLogger().warning("Download size mismatch: expected " + totalSize
                        + ", got " + tmp.length());
                tmp.delete();
                return false;
            }

            // 重命名到目标路径
            if (target.exists()) {
                target.delete();
            }
            if (!tmp.renameTo(target)) {
                plugin.getLogger().warning("Failed to rename temp file to " + target.getName());
                tmp.delete();
                return false;
            }

            plugin.getLogger().info("Download complete: " + target.getName());
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Download failed from " + url, e);
            tmp.delete();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 打开 HTTP 连接，处理 301/302 重定向。
     */
    private HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("Accept", "application/octet-stream");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(false);

        int code = conn.getResponseCode();
        if (code == 302 || code == 301) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) URI.create(location).toURL().openConnection();
            conn.setRequestProperty("Accept", "application/octet-stream");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
        }
        return conn;
    }

    /**
     * 简单的 JSON 字段提取：找 "tag_name": "xxx"
     */
    private String extractTag(String json) {
        String key = "\"tag_name\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;

        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;

        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;

        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;

        return json.substring(start + 1, end);
    }

    /**
     * 从 Release JSON 中提取 .jar 资源的下载地址。
     * 查找 assets 数组中第一个以 .jar 结尾的 browser_download_url。
     */
    private String extractDownloadUrl(String json) {
        int assetsIdx = json.indexOf("\"assets\"");
        if (assetsIdx < 0) return null;

        String dlKey = "\"browser_download_url\"";
        int searchFrom = assetsIdx;

        while (true) {
            int idx = json.indexOf(dlKey, searchFrom);
            if (idx < 0) return null;

            int colon = json.indexOf(':', idx + dlKey.length());
            if (colon < 0) return null;

            int start = json.indexOf('"', colon + 1);
            if (start < 0) return null;

            int end = json.indexOf('"', start + 1);
            if (end < 0) return null;

            String url = json.substring(start + 1, end);
            if (url.endsWith(".jar")) {
                return url;
            }

            searchFrom = end;
        }
    }

    /**
     * 语义化版本比较。去除前缀 v 后按 '.' 分段比较数字。
     */
    static boolean isNewer(String current, String latest) {
        String a = normalizeVersion(current);
        String b = normalizeVersion(latest);

        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");

        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? parseSegment(pa[i]) : 0;
            int vb = i < pb.length ? parseSegment(pb[i]) : 0;
            if (vb > va) return true;
            if (vb < va) return false;
        }
        return false;
    }

    private static String normalizeVersion(String v) {
        if (v == null) return "0";
        v = v.trim();
        if (v.startsWith("v")) v = v.substring(1);
        return v.isEmpty() ? "0" : v;
    }

    private static int parseSegment(String s) {
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public record UpdateResult(boolean hasUpdate, String latestVersion, String downloadUrl, String error) {}
}
