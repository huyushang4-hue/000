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
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject voiceProp = new JsonObject();
        voiceProp.addProperty("type", "string");
        voiceProp.addProperty("description", "音色英文名，如：Cherry, Serena, Ethan, Chelsie");
        props.add("voice", voiceProp);
        params.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("voice");
        params.add("required", required);
        return FunctionDefinition.builder()
                .name("change_voice").description("切换 TTS 语音音色").parameters(params).build();
    }

    @Override
    public String execute(JsonObject args) {
        String voice = args.get("voice").getAsString();
        log.info("Tool: change_voice, voice={}", voice);
        voiceConfig.set(voice);
        return "音色已切换为：" + voice;
    }
}
