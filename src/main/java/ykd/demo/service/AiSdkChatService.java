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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiSdkChatService {

    // 多模态对话客户端：负责和阿里云百炼服务器通信
    private final MultiModalConversation conversation;

    // 对话历史：记录用户和 AI 之间说过的所有话
    private final List<MultiModalMessage> messages;

    // 调用阿里云 API 需要的密钥，从系统环境变量读取
    private final String apiKey;

    private final ImageSynthesis imageSynthesis;

    // 构造方法：类被 Spring 创建时自动执行
    public AiSdkChatService() {
        // 从环境变量拿到 API Key
        this.apiKey = System.getenv("DASHSCOPE_API_KEY");

        // 创建一个多模态对话客户端
        this.conversation = new MultiModalConversation();

        // 创建空的对话历史列表
        this.messages = new ArrayList<>();

        this.imageSynthesis = new ImageSynthesis();
    }

    /**
     * 纯文字对话
     */
    public String chat(String userMessage) {
        // 如果用户发了空消息，直接返回提示
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

        } catch (ApiException | NoApiKeyException  | UploadFileException e) {
            log.error("调用多模态模型失败", e);
            return "AI 暂时无法回复，请稍后再试";
        }
    }

    /**
     * 图文对话
     */
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

        } catch (ApiException | NoApiKeyException  | UploadFileException e) {
            log.error("调用多模态模型识图失败", e);
            return "图片识别失败，请稍后再试";
        }
    }

    /**
     * 用 base64 图片进行图文对话
     */
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
    /**
     * 文生图
     * 用户输入文字描述，返回生成图片的 URL
     */
    public String generateImage(String prompt){
        if (prompt == null || prompt.isBlank()) {
            return "请告诉我你想画什么";
        }
        log.info("SDK 调用文生图，prompt={}", prompt);
        try {
            ImageSynthesisParam param = ImageSynthesisParam.builder()
                    .apiKey(apiKey)
                    .model("wanx2.1-t2i-turbo")  // 通义万相文生图模型
                    .prompt(prompt)
                    .n(1)                          // 生成 1 张
                    .size("1024*1024")             // 图片尺寸
                    .build();

            ImageSynthesisResult result = imageSynthesis.call(param);

            // 结果在 output.results 里，每个结果 map 里有 url 字段
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
}