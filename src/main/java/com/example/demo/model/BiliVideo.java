package com.example.demo.model;

import java.time.LocalDateTime;

/**
 * B站视频实体
 */
public class BiliVideo {
    /** BV号 */
    private String bvId;
    /** 视频标题 */
    private String title;
    /** 视频简介 */
    private String description;
    /** UP主名称 */
    private String upName;
    /** UP主mid */
    private Long upMid;
    /** 视频时长（秒） */
    private Integer duration;
    /** 分区 */
    private String tname;
    /** 字幕纯文本（AI字幕/人工字幕） */
    private String subtitleText;
    /** 收藏夹ID */
    private Long favId;
    /** 抓取时间 */
    private LocalDateTime fetchedAt;

    public String getBvId() { return bvId; }
    public void setBvId(String bvId) { this.bvId = bvId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getUpName() { return upName; }
    public void setUpName(String upName) { this.upName = upName; }

    public Long getUpMid() { return upMid; }
    public void setUpMid(Long upMid) { this.upMid = upMid; }

    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }

    public String getTname() { return tname; }
    public void setTname(String tname) { this.tname = tname; }

    public String getSubtitleText() { return subtitleText; }
    public void setSubtitleText(String subtitleText) { this.subtitleText = subtitleText; }

    public Long getFavId() { return favId; }
    public void setFavId(Long favId) { this.favId = favId; }

    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(LocalDateTime fetchedAt) { this.fetchedAt = fetchedAt; }

    @Override
    public String toString() {
        return "BiliVideo{" +
                "bvId='" + bvId + '\'' +
                ", title='" + title + '\'' +
                ", upName='" + upName + '\'' +
                ", duration=" + duration +
                '}';
    }
}
