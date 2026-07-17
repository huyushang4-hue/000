package ykd.demo.runner;

import com.github.wechat.ilink.sdk.ILinkClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import ykd.demo.service.ILinkBotService;

@Slf4j
@Component
public class ILinkBootstrapRunner implements CommandLineRunner {

    private final ILinkBotService ilinkBotService;

    public ILinkBootstrapRunner(ILinkBotService ilinkBotService) {
        this.ilinkBotService = ilinkBotService;
    }

    @Override
    public void run(String... args) {
        log.info("启动 iLink Bot...");
        new Thread(ilinkBotService::start, "ilink-bot-thread").start();
    }
}
