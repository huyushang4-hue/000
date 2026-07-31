package com.example.demo.service;

import com.example.demo.api.BiliApiClient;
import com.example.demo.model.BiliVideo;
import com.example.demo.sql.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 收藏夹业务服务
 */
public class FavService {

    private static final Logger log = LoggerFactory.getLogger(FavService.class);
    private final BiliApiClient apiClient;
    private final VideoRepository videoRepository;
    private final Long dedeUserId;

    public FavService(String cookie) {
        this.apiClient = new BiliApiClient(cookie);
        this.videoRepository = new VideoRepository();

        Pattern pattern = Pattern.compile("DedeUserID=(\\d+)");
        Matcher matcher = pattern.matcher(cookie);
        if (matcher.find()) {
            dedeUserId = Long.parseLong(matcher.group(1));
            //System.out.println("dedeUserId:"+dedeUserId);
        }else{
            dedeUserId = 0L;
            log.error("dedeUserId值未配置！");
        }
    }

    /**
     * 列出用户所有收藏夹
     */
    public Map<Long, String> listFavFolders() throws Exception {
        Map<Long, String> favMap = apiClient.listFavFolders(dedeUserId);
        for (Map.Entry<Long, String> entry : favMap.entrySet()) {
            Long id = entry.getKey();
            String name = entry.getValue();
            videoRepository.saveFav(id,name);
        }

        return apiClient.listFavFolders(dedeUserId);
    }

    /**
     * 抓取收藏夹视频元信息（标题+简介），不含字幕
     * 速度最快
     */
    public List<BiliVideo> fetchFavMeta(long favId) throws Exception {
        List<BiliVideo> videos = apiClient.listFavVideos(favId);
        List<BiliVideo> saved = new ArrayList<>();

        for (BiliVideo video : videos) {
            try {
                BiliVideo detail = apiClient.getVideoDetail(video.getBvId());
                detail.setFavId(favId);
                detail.setFetchedAt(LocalDateTime.now());

                videoRepository.saveVideo(detail);
                saved.add(detail);
                System.out.println("✅ 已保存: " + detail.getTitle() + " [" + detail.getBvId() + "]");
            } catch (Exception e) {
                System.err.println("❌ 失败 " + video.getBvId() + ": " + e.getMessage());
            }
        }
        System.out.println("📊 共抓取 " + saved.size() + " 个视频元信息");
        return saved;
    }

    /**
     * 抓取收藏夹视频 + 字幕纯文本存入数据库
     * 字幕文本可直接发给大模型分析视频内容
     */
    public int fetchFavWithSubtitle(long favId) throws Exception {
        List<BiliVideo> videos = apiClient.listFavVideos(favId);
        int count = 0;
        int withSubtitle = 0;

        for (BiliVideo video : videos) {
            try {
                // 1. 拉取视频详情（简介、cid）
                BiliVideo detail = apiClient.getVideoDetail(video.getBvId());
                detail.setFavId(favId);
                detail.setFetchedAt(LocalDateTime.now());

                // 2. 抓取字幕
                System.out.println("📝 获取字幕: " + detail.getTitle());
                String subtitle = apiClient.getVideoSubtitle(video.getBvId());
                if (subtitle != null && !subtitle.isBlank()) {
                    detail.setSubtitleText(subtitle);
                    withSubtitle++;
                    System.out.println("   ✅ 字幕获取成功 (" + subtitle.length() + " 字)");
                } else {
                    System.out.println("   ⚠️  该视频无字幕");
                }

                // 3. 存入数据库
                videoRepository.saveVideo(detail);
                count++;
            } catch (Exception e) {
                System.err.println("❌ 失败 " + video.getBvId() + ": " + e.getMessage());
            }
        }
        System.out.println("📊 共抓取 " + count + " 个视频，其中 " + withSubtitle + " 个含字幕");
        return count;
    }

    /**
     * 从数据库读取单个视频的字幕文本（可直接发给大模型）
     */
    public String getSubtitleText(String bvId) {
        BiliVideo video = videoRepository.getVideoByBvId(bvId);
        return video != null ? video.getSubtitleText() : null;
    }

    /**
     * 从数据库读取全部视频的字幕文本（可直接发给大模型）
     */
    public String getAllSubtitleText() {
        return videoRepository.getVideoSubtitleText();
    }

    /**
     * 列出数据库中所有视频元信息
     */
    public List<BiliVideo> listAllVideos() {
        return videoRepository.listAllVideosMeta();
    }

    /**
     * 统计有字幕的视频数量
     */
    public int countWithSubtitle() {
        return videoRepository.countVideosWithSubtitle();
    }

    public void close() {
        videoRepository.close();
    }
}
