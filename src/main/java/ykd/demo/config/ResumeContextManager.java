package ykd.demo.config;

import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;

/** 持久化 / 恢复 iLink 登录会话，支持多 Bot */
@Slf4j
@Component
public class ResumeContextManager {

    private static final String DIR = "sessions";
    private final Gson gson = new Gson();

    /** 保存（指定 botKey） */
    public void saveFrom(String botKey, LoginContext loginCtx) {
        saveToFile(botKey + ".json", loginCtx);
    }

    /** 保存（默认 bot，兼容旧调用） */
    public void saveFrom(LoginContext loginCtx) {
        saveToFile("default.json", loginCtx);
    }

    private void saveToFile(String fileName, LoginContext loginCtx) {
        try {
            Files.createDirectories(Path.of(DIR));
            JsonObject json = new JsonObject();
            json.addProperty("botToken", loginCtx.getBotToken());
            json.addProperty("userId", loginCtx.getUserId());
            json.addProperty("botId", loginCtx.getBotId());
            json.addProperty("baseUrl", loginCtx.getBaseUrl());
            Files.writeString(Path.of(DIR, fileName), gson.toJson(json));
            log.info("会话已保存 -> sessions/{}", fileName);
        } catch (IOException e) {
            log.error("保存 ResumeContext 失败", e);
        }
    }

    /** 加载（指定 botKey） */
    public ResumeContext load(String botKey) {
        return loadFromFile(botKey + ".json");
    }

    /** 加载（默认 bot，兼容旧调用） */
    public ResumeContext load() {
        return loadFromFile("default.json");
    }

    private ResumeContext loadFromFile(String fileName) {
        Path file = Path.of(DIR, fileName);
        if (!Files.exists(file)) return null;
        try {
            String content = Files.readString(file);
            JsonObject json = gson.fromJson(content, JsonObject.class);
            LoginContext loginCtx = new LoginContext(
                    json.get("botToken").getAsString(),
                    json.get("userId").getAsString(),
                    json.get("botId").getAsString(),
                    json.get("baseUrl").getAsString()
            );
            log.info("已加载已保存的会话: botId={}", json.get("botId").getAsString());
            return ResumeContext.builder(loginCtx).build();
        } catch (Exception e) {
            log.error("加载 ResumeContext 失败", e);
            return null;
        }
    }

    /** 删除（指定 botKey） */
    public void clear(String botKey) {
        try {
            Files.deleteIfExists(Path.of(DIR, botKey + ".json"));
        } catch (IOException ignored) {}
    }

    /** 删除（默认 bot） */
    public void clear() {
        try {
            Files.deleteIfExists(Path.of(DIR, "default.json"));
        } catch (IOException ignored) {}
    }
}
