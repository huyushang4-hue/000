package ykd.demo.tool.bilibili;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import ykd.demo.service.AiChatService;
import ykd.demo.service.BiliService;
import ykd.demo.service.impl.AiSdkChatServiceImpl;
import ykd.demo.tool.Tool;

/**
 * B 站视频音频转文字：下载视频音频 → DashScope ASR 识别 → 返回文字
 * 适用于没有字幕的视频，或需要更准确识别的场景
 */
@Slf4j
@Component
public class BiliVideoToTextTool implements Tool {

    private final BiliService biliService;
    private final AiChatService aiChatService;

    public BiliVideoToTextTool(BiliService biliService, @Lazy AiChatService aiChatService) {
        this.biliService = biliService;
        this.aiChatService = aiChatService;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return func("bili_video_to_text",
                "将B站视频的音频转换为文字。适用于没有字幕的视频，或需要完整音频转录的场景。输入视频BV号",
                """
                {"type":"object","properties":{
                    "bv_id":{"type":"string","description":"视频BV号，如 BV1xx411c7mD"}
                },"required":["bv_id"]}
                """);
    }

    @Override
    public String execute(JsonObject args) {
        String openId = AiSdkChatServiceImpl.getCurrentToolUser();
        if (openId == null) return "无法获取用户信息";

        String bvId = args.get("bv_id").getAsString();
        log.info("Tool: bili_video_to_text, bvId={}", bvId);

        if (!biliService.isLoggedIn(openId)) {
            return "你还没有登录B站。请先调用 bili_qr_login 扫码登录。";
        }

        try {
            // 1. 下载音频
            byte[] audio = biliService.downloadAudio(openId, bvId);
            if (audio == null || audio.length == 0) {
                return "无法下载视频音频，可能视频不存在或已删除。";
            }

            if (audio.length > 15 * 1024 * 1024) {
                return "音频文件过大（" + audio.length / 1024 / 1024 + "MB），暂不支持超过15MB的音频。";
            }

            // 2. ASR 识别
            String text = aiChatService.speechToText(audio);
            if (text == null || text.isBlank()) {
                return "音频识别完成，但未识别出有效文字（可能是纯音乐或无声视频）。";
            }

            // 3. 截断过长结果
            if (text.length() > 5000) {
                text = text.substring(0, 5000) + "\n\n[... 文字已截断，共 " + text.length() + " 字符]";
            }

            log.info("视频转文字成功: bvId={}, 文字长度={}", bvId, text.length());
            return String.format("视频 %s 的音频转录：\n\n%s", bvId, text);

        } catch (Exception e) {
            log.error("bili_video_to_text 失败", e);
            return "视频转文字失败：" + e.getMessage();
        }
    }
}
