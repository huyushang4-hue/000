package ykd.demo.tool.impl;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisOutput;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ykd.demo.tool.PendingMediaQueue;
import ykd.demo.tool.Tool;

@Slf4j
@Component
public class GenerateImageTool implements Tool {
    private final String apiKey = System.getenv("DASHSCOPE_API_KEY");
    private final ImageSynthesis imageSynthesis = new ImageSynthesis();
    private final PendingMediaQueue queue;

    public GenerateImageTool(PendingMediaQueue queue) {
        this.queue = queue;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return func("generate_image", "根据文字描述生成图片", """
            {"type":"object","properties":{"prompt":{"type":"string","description":"生成图片的文字描述，最好是英文"}},"required":["prompt"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String prompt = args.get("prompt").getAsString();
        log.info("Tool: generate_image, prompt={}", prompt);
        try {
            ImageSynthesisParam param = ImageSynthesisParam.builder()
                    .apiKey(apiKey).model("wanx2.1-t2i-turbo").prompt(prompt).n(1).size("1024*1024").build();
            ImageSynthesisResult result = imageSynthesis.call(param);
            ImageSynthesisOutput output = result.getOutput();
            if (output == null || output.getResults() == null || output.getResults().isEmpty())
                return "图片生成失败，没有返回结果";
            String imageUrl = output.getResults().get(0).get("url");
            log.info("image url: {}", imageUrl);
            if (imageUrl != null && imageUrl.startsWith("http")) {
                byte[] imgBytes = new java.net.URL(imageUrl).openStream().readAllBytes();
                if (imgBytes != null && imgBytes.length > 0) {
                    queue.add(imgBytes, "generated.png");
                }
            }
            return "图片已生成，请在回复中告知用户图片已发送。";
        } catch (Exception e) {
            log.error("generate_image failed", e);
            return "图片生成失败，请稍后再试";
        }
    }
}
