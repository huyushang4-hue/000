package ykd.demo.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
@Slf4j
@Service
public class AiChatService {
    //千问apikey
    private final String apiKey;
    //HTTP客户端，向阿里服务器发送POST请求
    private final RestClient restClient;
    // mapper：Jackson 的 JSON 解析器，把 API 返回的 JSON 字符串变成可读的树结构
    private final ObjectMapper mapper = new ObjectMapper();

    public AiChatService(){
        //读取系统环境，得到千问apikey
        this.apiKey=System.getenv("DASHSCOPE_API_KEY");
        //创建一个HTTP客户端
        this.restClient=RestClient.create();
    }
    public String getAiResponse(String txt){
        //判空
        if (txt==null||txt.isBlank()){
            return ("请输入想说的话");
        }
        //抛出异常
        log.info("向模型发送请求，txt={}",txt);
        //bodyjson拼写
        String requestBody = String.format(
                "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}]}",
                txt
        );
        //构建json
        String json=restClient.post()
                .uri("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(String.class);

        //try catch包裹
        try {
            JsonNode root=mapper.readTree(json);
            String reply=root.get("choices").get(0).get("message").get("content").asText();
            return reply;
        } catch (JsonProcessingException e) {
            log.error("解析千问响应失败：{}", json, e);
            return "AI 暂时无法回复，请稍后再试";
        }


    }


}
