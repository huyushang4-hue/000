package ykd.demo.tool.impl;

import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.tool.PendingMediaQueue;
import ykd.demo.tool.Tool;
import ykd.demo.tool.VoiceConfig;

import java.io.InputStream;
import java.net.URL;

@Slf4j
@Component
public class TextToSpeechTool implements Tool {
    private final String apiKey = System.getenv("DASHSCOPE_API_KEY");
    private final MultiModalConversation conversation = new MultiModalConversation();
    private final PendingMediaQueue queue;
    private final VoiceConfig voiceConfig;

    public TextToSpeechTool(PendingMediaQueue queue, VoiceConfig voiceConfig) {
        this.queue = queue;
        this.voiceConfig = voiceConfig;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return func("text_to_speech", "将文字转为语音文件（WAV格式）发送给用户", """
            {"type":"object","properties":{"text":{"type":"string","description":"要转为语音的文字内容"}},"required":["text"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String text = args.get("text").getAsString();
        log.info("Tool: text_to_speech, text={}", text);
        try {
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey).model("qwen3-tts-flash").text(text)
                    .voice(com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters.Voice.valueOf(voiceConfig.get().toUpperCase())).build();
            MultiModalConversationResult result = conversation.call(param);
            String audioUrl = result.getOutput().getAudio().getUrl();
            if (audioUrl == null || audioUrl.isBlank()) return "语音合成失败：无音频返回";
            log.info("TTS url: {}", audioUrl);
            try (InputStream in = new URL(audioUrl).openStream()) {
                byte[] audioBytes = in.readAllBytes();
                if (audioBytes.length > 0) {
                    queue.add(audioBytes, "语音回复.wav");
                    return "语音已生成并发送给用户";
                }
            }
            return "语音合成失败：下载音频为空";
        } catch (Exception e) {
            log.error("text_to_speech failed", e);
            return "语音合成失败，请稍后再试";
        }
    }
}
