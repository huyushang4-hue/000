package ykd.demo.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 暂存微信用户最新发的附件（图片/文档），供 Tool 读取 */
@Slf4j
@Component
public class AttachmentStore {
    private volatile String base64Data;
    private volatile String prompt = "描述这张图片";

    public void set(String base64Data, String prompt) {
        this.base64Data = base64Data;
        this.prompt = prompt == null || prompt.isBlank() ? "描述这张图片" : prompt;
    }

    public String getBase64() { return base64Data; }
    public String getPrompt() { return prompt; }

    public void clear() { base64Data = null; }
}