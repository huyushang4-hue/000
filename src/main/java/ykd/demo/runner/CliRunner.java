package ykd.demo.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;

import org.springframework.stereotype.Component;
import ykd.demo.service.AiChatService;
import ykd.demo.service.WeatherService;
import java.util.Scanner;

@Slf4j
@Component
public class CliRunner implements CommandLineRunner {
    private static final String VERSION ="1.0.0";
    private final long startTime;
    private final WeatherService weatherService; // final 保证注入后不被篡改
    private final AiChatService aiChatService;
    public CliRunner(WeatherService weatherService,AiChatService aiChatService) {
        this.weatherService = weatherService;
        this.startTime = System.currentTimeMillis();
        this.aiChatService=aiChatService;
    }


    @Override
    public void run(String... args) {

            Scanner scanner = new Scanner(System.in);
            System.out.println("程序已启动，输入命令（输入 help 查看帮助，输入 exit 退出）");
            while (true) {
                try {
                    System.out.print(">");
                    String line = scanner.nextLine().trim().toLowerCase();
                    if (line.isEmpty()) continue;  // 空行跳过

                    switch (line) {

                        case "/help":
                            showHelp();
                            break;
                        case "/version":
                            System.out.println("程序版本：" + VERSION);
                            break;
                        case "/status":
                            showStatus();
                            break;
                        case "/test":
                            int x=1/0;
                            break;
                        case "/qwen": {
                            System.out.println("进入聊天模式，输入 exit 退出");
                            while (true) {
                                System.out.print("你> ");
                                String msg = scanner.nextLine().trim();
                                if (msg.equalsIgnoreCase("exit")) break;
                                String result1 = aiChatService.chat(msg);
                                System.out.println("AI> " + result1);
                            }
                            break;
                        }
                        case "/weather":
                            System.out.print("请输入城市名：");
                            String city = scanner.nextLine().trim();
                            String result = weatherService.getWeather(city);
                            System.out.println(result);
                            break;
                        case "/exit":
                            System.out.println("再见！");
                            return;   // 结束 run()，程序退出
                        default:
                            System.out.println("未知命令，输入 help 查看帮助");
                    }
                }catch (Exception e) {                                    // ← catch 挪到这
                    log.error("命令执行失败", e);
                    System.err.println("错误：" + e.getMessage());
                }

            }


    }
    private void showHelp() {
        System.out.println("========== 帮助 ==========");
        System.out.println("help     - 显示此帮助信息");
        System.out.println("version  - 显示程序版本");
        System.out.println("status   - 显示程序运行状态");
        System.out.println("exit     - 退出程序");
        System.out.println("==========================");
    }

    private void showStatus() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        long totalMB = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        long freeMB  = Runtime.getRuntime().freeMemory()  / 1024 / 1024;
        int  cores   = Runtime.getRuntime().availableProcessors();

        System.out.println("=== 运行状态 ===");
        System.out.println("运行时长：" + uptime + " 秒");
        System.out.println("内存使用：" + freeMB + "MB / " + totalMB + "MB");
        System.out.println("CPU 核数：" + cores);
        System.out.println("===============");
    }
}

