package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.tool.Tool;
import ykd.demo.tool.VoiceConfig;

@Slf4j
@Component
public class ChangeVoiceTool implements Tool {
    private final VoiceConfig voiceConfig;

    public ChangeVoiceTool(VoiceConfig voiceConfig) {
        this.voiceConfig = voiceConfig;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return func("change_voice","切换tts语音", """
                {"type":"object","properties":{"voice":{"type":"string","description":"音色英文名，如：Cherry, Serena, Ethan, Chelsie"}},"required":["voice"]}""");
    }

    @Override
    public String execute(JsonObject args) {
        String voice = args.get("voice").getAsString();
        log.info("Tool: change_voice, voice={}", voice);
        voiceConfig.set(voice);
        return "音色已切换为：" + voice;
    }
}
