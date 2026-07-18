package ykd.demo.service;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisOutput;

import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiSdkChatService {

    private final MultiModalConversation conversation;
    private final List<MultiModalMessage> messages;
    private final String apiKey;
    private final ImageSynthesis imageSynthesis;

    public AiSdkChatService() {
        this.apiKey = System.getenv("DASHSCOPE_API_KEY");
        this.conversation = new MultiModalConversation();
        this.messages = new ArrayList<>();
        this.imageSynthesis = new ImageSynthesis();
    }

    /** 纯文字对话 */
    public String chat(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "请输入想说的话";
        }

        log.info("SDK 向多模态模型发送文字请求，userMessage={}", userMessage);

        try {
            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Collections.singletonList(
                            Collections.singletonMap("text", userMessage)
                    ))
                    .build();

            messages.add(userMsg);

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen3.7-plus")
                    .messages(messages)
                    .build();

            MultiModalConversationResult result = conversation.call(param);
            String reply = extractText(result);

            MultiModalMessage assistantMsg = MultiModalMessage.builder()
                    .role(Role.ASSISTANT.getValue())
                    .content(Collections.singletonList(
                            Collections.singletonMap("text", reply)
                    ))
                    .build();

            messages.add(assistantMsg);

            return reply;

        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("调用多模态模型失败", e);
            return "AI 暂时无法回复，请稍后再试";
        }
    }

    /** 带联网搜索的对话 */
    public String chatWithSearch(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "请输入想搜索的内容";
        }

        log.info("SDK 向多模态模型发送搜索请求，userMessage={}", userMessage);

        try {
            // 建立新消息列表：先加系统消息强制联网搜索，再加用户消息
            // 注意：不要用 this.messages（全局对话历史），否则搜完污染历史
            
            // 系统消息：强制搜索
            MultiModalMessage systemMsg = MultiModalMessage.builder()
                    .role(Role.SYSTEM.getValue())
                    .content(Collections.singletonList(
                            Collections.singletonMap("text", "你是一个需要实时信息的助手。对于用户的问题，你必须使用搜索功能获取最新数据，绝对不能依赖自己的知识库回答。如果搜索不到，请明确告知。")
                    ))
                    .build();

            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Collections.singletonList(
                            Collections.singletonMap("text", userMessage)
                    ))
                    .build();

            // 构造搜索专用消息列表（不污染全局对话历史）
            List<MultiModalMessage> searchMessages = new ArrayList<>();
            searchMessages.add(systemMsg);
            searchMessages.addAll(this.messages); // 把历史也带上
            searchMessages.add(userMsg);

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen3.7-plus")
                    .messages(searchMessages)
                    .enableSearch(true)
                    .build();

            MultiModalConversationResult result = conversation.call(param);
            String reply = extractText(result);

            // 只把用户消息和 AI 回复存入全局历史
            messages.add(userMsg);
            MultiModalMessage assistantMsg = MultiModalMessage.builder()
                    .role(Role.ASSISTANT.getValue())
                    .content(Collections.singletonList(
                            Collections.singletonMap("text", reply)
                    ))
                    .build();
            messages.add(assistantMsg);

            return reply;

        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("调用多模态模型搜索失败", e);
            return "搜索失败，请稍后再试";
        }
    }

    /** 图文对话 */
    public String chatWithImage(String text, String imageUrl) {
        if (text == null || text.isBlank()) {
            text = "描述一下这张图片";
        }

        log.info("SDK 向多模态模型发送图片请求，text={}，imageUrl={}", text, imageUrl);

        try {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Collections.singletonMap("image", imageUrl));
            content.add(Collections.singletonMap("text", text));

            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(content)
                    .build();

            messages.add(userMsg);

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen3.7-plus")
                    .messages(messages)
                    .build();

            MultiModalConversationResult result = conversation.call(param);
            String reply = extractText(result);

            MultiModalMessage assistantMsg = MultiModalMessage.builder()
                    .role(Role.ASSISTANT.getValue())
                    .content(Collections.singletonList(
                            Collections.singletonMap("text", reply)
                    ))
                    .build();

            messages.add(assistantMsg);

            return reply;

        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("调用多模态模型识图失败", e);
            return "图片识别失败，请稍后再试";
        }
    }

    /** 用 base64 图片进行图文对话 */
    public String chatWithImageBase64(String text, String base64Image) {
        if (text == null || text.isBlank()) {
            text = "描述一下这张图片";
        }

        log.info("SDK 向多模态模型发送 base64 图片请求，text={}", text);

        try {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Collections.singletonMap("image", "data:image/jpeg;base64," + base64Image));
            content.add(Collections.singletonMap("text", text));

            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(content)
                    .build();

            messages.add(userMsg);

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen3.7-plus")
                    .messages(messages)
                    .build();

            MultiModalConversationResult result = conversation.call(param);
            String reply = extractText(result);

            MultiModalMessage assistantMsg = MultiModalMessage.builder()
                    .role(Role.ASSISTANT.getValue())
                    .content(Collections.singletonList(
                            Collections.singletonMap("text", reply)
                    ))
                    .build();

            messages.add(assistantMsg);

            return reply;

        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("调用多模态模型识图失败", e);
            return "图片识别失败，请稍后再试";
        }
    }

    private String extractText(MultiModalConversationResult result) {
        List<Map<String, Object>> contentList = result.getOutput()
                .getChoices()
                .get(0)
                .getMessage()
                .getContent();

        for (Map<String, Object> item : contentList) {
            if (item.containsKey("text")) {
                Object textObj = item.get("text");
                return textObj != null ? textObj.toString() : "";
            }
        }
        return "";
    }

    /** 文生图 */
    public String generateImage(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "请告诉我你想画什么";
        }
        log.info("SDK 调用文生图，prompt={}", prompt);
        try {
            ImageSynthesisParam param = ImageSynthesisParam.builder()
                    .apiKey(apiKey)
                    .model("wanx2.1-t2i-turbo")
                    .prompt(prompt)
                    .n(1)
                    .size("1024*1024")
                    .build();

            ImageSynthesisResult result = imageSynthesis.call(param);

            ImageSynthesisOutput output = result.getOutput();
            if (output == null || output.getResults() == null || output.getResults().isEmpty()) {
                return "图片生成失败，没有返回结果";
            }

            String imageUrl = output.getResults().get(0).get("url");
            log.info("图片生成成功，url={}", imageUrl);
            return imageUrl;

        } catch (ApiException | NoApiKeyException e) {
            log.error("调用文生图失败", e);
            return "图片生成失败，请稍后再试";
        }
    }

    /** 文字转语音（TTS） */
    public byte[] textToSpeech(String text) {
        if (text == null || text.isBlank()) {
            return new byte[0];
        }

        log.info("TTS 合成语音，text={}", text);

        try {
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen3-tts-flash")
                    .text(text)
                    .voice(com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters.Voice.CHERRY)
                    .build();

            MultiModalConversationResult result = conversation.call(param);

            String audioUrl = result.getOutput().getAudio().getUrl();
            if (audioUrl == null || audioUrl.isBlank()) {
                log.warn("TTS 返回空 URL");
                return new byte[0];
            }

            log.info("TTS 合成成功，audioUrl={}", audioUrl);

            try (java.io.InputStream in = new java.net.URL(audioUrl).openStream()) {
                byte[] audioBytes = in.readAllBytes();
                log.info("TTS 音频下载成功，大小={} 字节", audioBytes.length);
                return audioBytes;
            }

        } catch (Exception e) {
            log.error("TTS 合成失败", e);
            return new byte[0];
        }
    }

    /** 语音转文字（ASR） */
    public String speechToText(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) {
            return "";
        }

        log.info("ASR 开始识别语音，大小={} 字节", audioBytes.length);

        try {
            String base64Audio = Base64.getEncoder().encodeToString(audioBytes);

            String requestBody = String.format("""
                {
                    "model": "qwen3-asr-flash",
                    "messages": [
                        {
                            "role": "user",
                            "content": [
                                {
                                    "type": "audio",
                                    "audio": "data:audio/wav;base64,%s"
                                }
                            ]
                        }
                    ]
                }
                """, base64Audio);

            RestClient asrClient = RestClient.builder()
                    .baseUrl("https://dashscope.aliyuncs.com")
                    .build();

            String responseJson = asrClient.post()
                    .uri("/compatible-mode/v1/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseJson);
            String text = root.path("choices").get(0).path("message").path("content").asText();

            log.info("ASR 识别结果：{}", text);
            return text;

        } catch (Exception e) {
            log.error("ASR 识别失败", e);
            return "";
        }
    }
}