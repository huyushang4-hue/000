package ykd.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
public class DemoApplication {
	private static final String API_KEY = System.getenv("A_QWEN_API_KEY");

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
		log.info("项目启动了");
		try {
			throw new RuntimeException("模拟一个异常");
		} catch (Exception e) {
			log.error("出错了", e);
		}

	}
}