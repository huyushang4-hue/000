package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ykd.demo.tool.Tool;

@Slf4j
@Component
public class ListVoicesTool implements Tool {

    private static final String VOICES = """
            可用音色（女声）：Cherry, Serena, Chelsie, Jada, Sunny, Jennifer, Kiki, Mia, Nini, Stella, Bella, Vivian, Mochi, Moon, Maia
            可用音色（男声）：Ethan, Li, Marcus, Roy, Peter, Eric, Rocky, Kai, Neil, Arthur, Aiden, Vincent, Ryan, Dylan, Elias, Andre, Emilien, Leo, Sam
            可切换当前音色，也可以让 AI 在回复 TTS 时用指定音色。""";

    @Override
    public FunctionDefinition getDefinition() {
        return FunctionDefinition.builder()
                .name("list_voices").description("列出所有可用的 TTS 音色").parameters(new JsonObject()).build();
    }

    @Override
    public String execute(JsonObject args) {
        log.info("Tool: list_voices");
        return VOICES;
    }
}
