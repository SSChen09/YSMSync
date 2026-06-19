package com.ysmsync.update;

import com.ysmsync.YSMPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * 检查 YSMSync 是否有新版本。
 * 通过 GitHub Releases API 获取最新版本号并进行语义化版本比较。
 */
public class UpdateChecker {

    private static final String GITHUB_API = "https://api.github.com/repos/SSChen09/YSMSync/releases/latest";

    private final YSMPlugin plugin;
    private volatile String latestVersion;
    private volatile boolean checking = false;

    public UpdateChecker(YSMPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 异步检查更新。
     */
    public CompletableFuture<UpdateResult> check() {
        if (checking) {
            return CompletableFuture.completedFuture(new UpdateResult(false, null, null, null));
        }
        checking = true;

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

                // 简单解析 tag_name 字段
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
                checking = false;
            }
        });
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    /**
     * 下载 JAR 文件到指定目标路径。
     * 先下载到 .tmp 临时文件，完成后重命名，防止下载中断导致文件损坏。
     *
     * @param downloadUrl GitHub Release 资源下载地址
     * @param target 目标文件路径
     * @return 下载是否成功
     */
    public boolean download(String downloadUrl, File target) {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(downloadUrl).toURL().openConnection();
            conn.setRequestProperty("Accept", "application/octet-stream");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            // GitHub 302 重定向
            if (code == 302 || code == 301) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                conn = (HttpURLConnection) URI.create(location).toURL().openConnection();
                conn.setRequestProperty("Accept", "application/octet-stream");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                code = conn.getResponseCode();
            }

            if (code != 200) {
                plugin.getLogger().warning("Download failed: HTTP " + code);
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

            // 下载完成，重命名替换目标文件
            File old = new File(target.getAbsolutePath() + ".old");
            if (target.exists()) {
                target.renameTo(old);
            }
            if (!tmp.renameTo(target)) {
                plugin.getLogger().warning("Failed to rename temp file to " + target.getName());
                tmp.delete();
                return false;
            }

            plugin.getLogger().info("Download complete: " + target.getName());
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Download failed", e);
            tmp.delete();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
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
        // 查找 assets 数组
        int assetsIdx = json.indexOf("\"assets\"");
        if (assetsIdx < 0) return null;

        // 查找第一个 browser_download_url
        String dlKey = "\"browser_download_url\"";
        int idx = json.indexOf(dlKey, assetsIdx);
        if (idx < 0) return null;

        int colon = json.indexOf(':', idx + dlKey.length());
        if (colon < 0) return null;

        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;

        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;

        String url = json.substring(start + 1, end);
        // 确保是 .jar 文件
        if (url.endsWith(".jar")) {
            return url;
        }

        // 如果第一个不是 .jar，继续找下一个
        return extractDownloadUrl(json, end);
    }

    private String extractDownloadUrl(String json, int fromIndex) {
        String dlKey = "\"browser_download_url\"";
        int idx = json.indexOf(dlKey, fromIndex);
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
        return extractDownloadUrl(json, end);
    }

    /**
     * 语义化版本比较。去除前缀 v 后按 '.' 分段比较数字。
     * 例：1.3.1 vs 1.4.0 → true, 1.3.1 vs 1.3.1 → false
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
