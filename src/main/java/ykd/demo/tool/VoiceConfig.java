package ykd.demo.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 共享音色配置——TextToSpeechTool 读，ChangeVoiceTool 写 */
@Slf4j
@Component
public class VoiceConfig {
    private volatile String currentVoice = "Cherry";

    public String get() { return currentVoice; }

    public void set(String voice) {
        this.currentVoice = voice;
        log.info("voice config changed: {}", voice);
    }
}
