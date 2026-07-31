package com.example.demo.sql;

import com.example.demo.model.BiliVideo;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B站视频数据仓库 - SQLite存储
 * 包含视频元信息 + 字幕纯文本
 */
public class VideoRepository {
    private static final String DB_PATH = "data/videos.db";
    private Connection connection;

    public VideoRepository() {
        try {
            // 确保目录存在
            new java.io.File("data").mkdirs();

            // 连接数据库（自动创建文件）
            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);

            // 创建表
            createTableIfNotExists();
            createFavTableIfNotExists();

            System.out.println("✅ SQLite 数据库已连接videos: " + DB_PATH);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 创建视频表
     */
    private void createTableIfNotExists() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS videos (
                bv_id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT,
                up_name TEXT,
                up_mid INTEGER,
                duration INTEGER,
                tname TEXT,
                fav_id INTEGER,
                subtitle_text TEXT,
                fetched_at TEXT NOT NULL
            )
        """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * 创建收藏夹表
     */
    private void createFavTableIfNotExists() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS favs (
                fav_id INTEGER PRIMARY KEY,
                name TEXT
            )
        """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * 保存收藏夹映射
     */
    public void saveFav(Long id,String name) {
        String sql = """
            INSERT OR REPLACE INTO favs
            (fav_id, name)
            VALUES (?, ?)
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setObject(1, id, Types.INTEGER);
            pstmt.setString(2, name);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 保存视频（含字幕文本）
     */
    public void saveVideo(BiliVideo video) {
        String sql = """
            INSERT OR REPLACE INTO videos
            (bv_id, title, description, up_name, up_mid, duration, tname, fav_id, subtitle_text, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, video.getBvId());
            pstmt.setString(2, video.getTitle());
            pstmt.setString(3, video.getDescription());
            pstmt.setString(4, video.getUpName());
            pstmt.setObject(5, video.getUpMid(), Types.INTEGER);
            pstmt.setObject(6, video.getDuration(), Types.INTEGER);
            pstmt.setString(7, video.getTname());
            pstmt.setObject(8, video.getFavId(), Types.INTEGER);
            pstmt.setString(9, video.getSubtitleText());
            pstmt.setString(10, video.getFetchedAt().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 查询全部收藏夹
     */
    public Map<Long,String> getFavs() {
        String sql = "SELECT * FROM favs";
        Map<Long,String> idToName = new HashMap<>();

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {

                    String id = rs.getString("fav_id");
                    String name = rs.getString("name");
                    idToName.put(Long.valueOf(id),name);

                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return idToName;
    }

    /**
     * 根据BV号查询视频（含字幕）
     */
    public BiliVideo getVideoByBvId(String bvId) {
        String sql = "SELECT * FROM videos WHERE bv_id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, bvId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rowToVideo(rs);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 查询全部视频字幕
     */
    public String getVideoSubtitleText() {
        String sql = "SELECT title, fav_id, subtitle_text FROM videos";
        Map<String,Map<String,String>> favToText = new HashMap<>();
        Map<String,String> bvToText = new HashMap<>();

        Map<Long, String> favs = getFavs();

        try (PreparedStatement pstmt = connection.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {

                String subtitleText = rs.getString("subtitle_text");
                String title = rs.getString("title");
                String favId = rs.getString("fav_id");
                String name = favs.get(Long.valueOf(favId));
                if (subtitleText!=null){
                    subtitleText = subtitleText.length() > 500 ? subtitleText.substring(0, 500) + "..." : subtitleText;
                    bvToText.put(title,subtitleText);
                    favToText.put(name,bvToText);
                }

            }
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }

        String result = favToText.toString();
        return result.isEmpty() ? null : result;
    }

    /**
     * 查询所有视频元信息（不含字幕，列表展示用）
     */
    public List<BiliVideo> listAllVideosMeta() {
        List<BiliVideo> videos = new ArrayList<>();
        String sql = "SELECT bv_id, title, description, up_name, up_mid, duration, tname, fav_id, fetched_at " +
                "FROM videos ORDER BY fetched_at DESC";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                BiliVideo video = new BiliVideo();
                video.setBvId(rs.getString("bv_id"));
                video.setTitle(rs.getString("title"));
                video.setDescription(rs.getString("description"));
                video.setUpName(rs.getString("up_name"));
                video.setUpMid(rs.getLong("up_mid"));
                video.setDuration(rs.getInt("duration"));
                video.setTname(rs.getString("tname"));
                video.setFavId(rs.getLong("fav_id"));
                video.setFetchedAt(LocalDateTime.parse(rs.getString("fetched_at")));
                videos.add(video);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return videos;
    }

    /**
     * 删除视频
     */
    public void deleteVideo(String bvId) {
        String sql = "DELETE FROM videos WHERE bv_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, bvId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 统计视频数量
     */
    public int countVideos() {
        String sql = "SELECT COUNT(*) AS cnt FROM videos";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt("cnt");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * 统计有字幕的视频数量
     */
    public int countVideosWithSubtitle() {
        String sql = "SELECT COUNT(*) AS cnt FROM videos WHERE subtitle_text IS NOT NULL AND subtitle_text != ''";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt("cnt");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private BiliVideo rowToVideo(ResultSet rs) throws SQLException {
        BiliVideo video = new BiliVideo();
        video.setBvId(rs.getString("bv_id"));
        video.setTitle(rs.getString("title"));
        video.setDescription(rs.getString("description"));
        video.setUpName(rs.getString("up_name"));
        video.setUpMid(rs.getLong("up_mid"));
        video.setDuration(rs.getInt("duration"));
        video.setTname(rs.getString("tname"));
        video.setFavId(rs.getLong("fav_id"));
        video.setSubtitleText(rs.getString("subtitle_text"));
        video.setFetchedAt(LocalDateTime.parse(rs.getString("fetched_at")));
        return video;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
