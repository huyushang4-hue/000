package ykd.demo.service;

import com.example.demo.BiliQrLogin;
import com.example.demo.service.FavService;
import com.example.demo.model.BiliVideo;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B站收藏夹服务：管理登录状态 + 封装 FavService
 * 多用户支持：每个用户独立的 cookie 和 FavService 实例
 */
@Slf4j
@Service
public class BiliService {

    /** 用户 cookie 缓存：key=openId, value=cookie 字符串 */
    private final ConcurrentHashMap<String, String> cookieCache = new ConcurrentHashMap<>();

    /** 用户 FavService 缓存：key=openId */
    private final ConcurrentHashMap<String, FavService> favServiceCache = new ConcurrentHashMap<>();

    /** 用户 QR 登录状态：key=openId, value=登录状态信息 */
    private final ConcurrentHashMap<String, QRLoginState> qrLoginStates = new ConcurrentHashMap<>();

    // ==================== 登录管理 ====================

    /**
     * 检查用户是否已登录
     */
    public boolean isLoggedIn(String openId) {
        return cookieCache.containsKey(openId);
    }

    /**
     * 获取用户的 cookie
     */
    public String getCookie(String openId) {
        return cookieCache.get(openId);
    }

    /**
     * 保存用户的 cookie（扫码登录成功后调用）
     */
    public void saveCookie(String openId, String cookie) {
        cookieCache.put(openId, cookie);
        // 清除旧的 FavService 缓存，用新 cookie 重建
        favServiceCache.remove(openId);
        log.info("B站 cookie 已保存: openId={}", openId);
    }

    /**
     * 清除用户的登录状态
     */
    public void clearLogin(String openId) {
        cookieCache.remove(openId);
        FavService old = favServiceCache.remove(openId);
        if (old != null) old.close();
        qrLoginStates.remove(openId);
        log.info("B站登录状态已清除: openId={}", openId);
    }

    /**
     * 获取用户的 FavService（懒初始化）
     * 每个用户使用独立工作目录，避免 SQLite 数据库共享导致数据混乱
     */
    private synchronized FavService getFavService(String openId) {
        FavService cached = favServiceCache.get(openId);
        if (cached != null) return cached;
        String cookie = cookieCache.get(openId);
        if (cookie == null) return null;

        // 每个用户独立目录，FavService 内部会在该目录下创建 data/videos.db
        String userDir = "data/bili_users/" + openId.replaceAll("[^a-zA-Z0-9]", "_");
        String originalDir = System.getProperty("user.dir");
        try {
            new java.io.File(userDir).mkdirs();
            System.setProperty("user.dir", new java.io.File(userDir).getAbsolutePath());
            FavService service = new FavService(cookie);
            favServiceCache.put(openId, service);
            log.info("FavService 已初始化: openId={}, dbDir={}", openId, userDir);
            return service;
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    // ==================== QR 登录流程 ====================

    /**
     * 开始 QR 登录：生成二维码信息
     * @return QRLoginState 包含二维码 URL 和 key
     */
    public QRLoginState startQRLogin(String openId) throws Exception {
        BiliQrLogin login = new BiliQrLogin();
        JsonNode genJson = login.getUrl();
        String qrPageUrl = login.buildQr(genJson);
        String qrcodeKey = genJson.path("data").path("qrcode_key").asText();
        String qrContentUrl = genJson.path("data").path("url").asText();

        QRLoginState state = new QRLoginState();
        state.qrcodeKey = qrcodeKey;
        state.qrContentUrl = qrContentUrl;
        state.qrPageUrl = qrPageUrl;
        state.biliQrLogin = login;
        state.genJson = genJson;

        qrLoginStates.put(openId, state);
        log.info("B站 QR 登录已启动: openId={}, qrPageUrl={}", openId, qrPageUrl);
        return state;
    }

    /**
     * 轮询 QR 登录状态
     * @return cookie 字符串（成功时），null（等待中），"expired"（过期）
     */
    public String pollQRLogin(String openId) {
        QRLoginState state = qrLoginStates.get(openId);
        if (state == null) return null;

        try {
            log.info("调用 BiliQrLogin.login, openId={}", openId);
            String cookie = state.biliQrLogin.login(3, state.genJson);
            log.info("BiliQrLogin.login 返回: cookie={}", cookie != null ? "长度" + cookie.length() : "null");
            if (cookie != null && !cookie.isBlank()) {
                saveCookie(openId, cookie);
                qrLoginStates.remove(openId);
                return cookie;
            }
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            log.info("QR 轮询超时/异常: msg={}", msg);
            if (msg != null && msg.contains("过期")) {
                log.info("二维码已过期，停止轮询: openId={}", openId);
                qrLoginStates.remove(openId);
                return "expired";
            }
            // 超时 = 还没扫到，继续轮询
        } catch (Exception e) {
            log.warn("QR 登录轮询异常: {}", e.getMessage(), e);
        }
        return null;
    }

    // ==================== 收藏夹操作 ====================

    /**
     * 列出用户所有收藏夹
     */
    public Map<Long, String> listFavFolders(String openId) throws Exception {
        FavService favService = getFavService(openId);
        if (favService == null) throw new RuntimeException("未登录B站");
        return favService.listFavFolders();
    }

    /**
     * 抓取收藏夹视频元信息（不含字幕，速度快）
     * @return 抓取成功的视频列表
     */
    public List<BiliVideo> fetchFavMeta(String openId, long favId) throws Exception {
        FavService favService = getFavService(openId);
        if (favService == null) throw new RuntimeException("未登录B站");
        return favService.fetchFavMeta(favId);
    }

    /**
     * 抓取收藏夹视频 + 字幕（速度慢但内容全）
     */
    public int fetchFavWithSubtitle(String openId, long favId) throws Exception {
        FavService favService = getFavService(openId);
        if (favService == null) throw new RuntimeException("未登录B站");
        return favService.fetchFavWithSubtitle(favId);
    }

    /**
     * 列出数据库中所有视频
     */
    public List<BiliVideo> listAllVideos(String openId) {
        FavService favService = getFavService(openId);
        if (favService == null) return List.of();
        return favService.listAllVideos();
    }

    /**
     * 获取某个视频的字幕
     */
    public String getSubtitle(String openId, String bvId) {
        FavService favService = getFavService(openId);
        if (favService == null) return null;
        return favService.getSubtitleText(bvId);
    }

    /**
     * 获取所有视频的字幕文本（用于 RAG 知识库）
     */
    public String getAllSubtitleText(String openId) {
        FavService favService = getFavService(openId);
        if (favService == null) return null;
        return favService.getAllSubtitleText();
    }

    // ==================== 视频音频下载 ====================

    /**
     * 下载 B 站视频的音频流
     * @return 音频字节数组（m4a 格式），失败返回 null
     */
    /**
     * 删除收藏夹
     */
    public boolean deleteFavFolder(String openId, long favId) throws Exception {
        FavService favService = getFavService(openId);
        if (favService == null) throw new RuntimeException("未登录B站");
        return favService.deleteFolder(favId);
    }

    /** 从指定收藏夹移除视频 */
    public boolean deleteFavVideo(String openId, long favId, String bvId) throws Exception {
        FavService favService = getFavService(openId);
        if (favService == null) throw new RuntimeException("未登录B站");
        return favService.deleteVideo(favId, bvId);
    }

    public byte[] downloadAudio(String openId, String bvId) {
        String cookie = cookieCache.get(openId);
        if (cookie == null) return null;

        try {
            var client = java.net.http.HttpClient.newHttpClient();

            // 1. 获取视频 cid
            String infoUrl = "https://api.bilibili.com/x/web-interface/view?bvid=" + bvId;
            var infoReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(infoUrl))
                    .header("Cookie", cookie)
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            String infoJson = client.send(infoReq, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
            JsonNode infoRoot = new com.fasterxml.jackson.databind.ObjectMapper().readTree(infoJson);
            long cid = infoRoot.path("data").path("cid").asLong();
            String title = infoRoot.path("data").path("title").asText("unknown");
            if (cid == 0) {
                log.warn("获取 cid 失败: bvId={}", bvId);
                return null;
            }

            // 2. 获取音频流地址（fnval=16 = DASH 格式，分离音视频）
            String playUrl = "https://api.bilibili.com/x/player/wbi/playurl?bvid=" + bvId
                    + "&cid=" + cid + "&fnval=16&qn=64";
            var playReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(playUrl))
                    .header("Cookie", cookie)
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            String playJson = client.send(playReq, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
            JsonNode playRoot = new com.fasterxml.jackson.databind.ObjectMapper().readTree(playJson);
            String audioUrl = playRoot.path("data").path("dash").path("audio").get(0).path("baseUrl").asText();
            if (audioUrl == null || audioUrl.isBlank()) {
                log.warn("获取音频 URL 失败: bvId={}", bvId);
                return null;
            }

            // 3. 下载音频（需要 Referer 防盗链）
            var audioReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(audioUrl))
                    .header("Cookie", cookie)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://www.bilibili.com")
                    .GET().build();
            byte[] audio = client.send(audioReq, java.net.http.HttpResponse.BodyHandlers.ofByteArray()).body();
            log.info("音频下载成功: title={}, size={}KB", title, audio.length / 1024);
            return audio;

        } catch (Exception e) {
            log.error("下载音频失败: bvId={}", bvId, e);
            return null;
        }
    }

    // ==================== 登录状态内部类 ====================

    public static class QRLoginState {
        public String qrcodeKey;
        public String qrContentUrl;
        public String qrPageUrl;
        public BiliQrLogin biliQrLogin;
        public JsonNode genJson;
    }
}
