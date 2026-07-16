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


	}
}