package ykd.demo.skill;

import com.alibaba.dashscope.tools.*;
import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import ykd.demo.tool.Tool;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 核心加载器：两层设计
 *   Tool 层 — tools/*.json → 反射实例化为可执行工具
 *   Skill 层 — skills/*.md → 解析为纯知识/工作流提示词
 *
 * 启动时: Tool 建 FunctionDefinition + Skill 目录注入 system prompt
 * 运行时: AI 调 load_skill(name) → 返回对应 SKILL.md 正文（按需加载）
 */
@Slf4j
@Component
public class SkillManager {

    // ========== Tool 层 ==========
    private final List<Tool> tools = new ArrayList<>();
    private final List<ToolBase> toolDefs = new ArrayList<>();
    private final Map<String, Tool> toolByName = new HashMap<>();

    // ========== Skill 层 ==========
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    private static final Gson gson = new Gson();

    /** 加载全部：tools/*.json + skills/*.md */
    @PostConstruct
    public void load() {
        loadTools();
        loadSkills();
        log.info("SkillManager 加载完成: {} 工具 + {} 技能", tools.size(), skills.size());
    }

    // ==================== Tool 加载 ====================

    private void loadTools() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:tools/*.json");
            for (Resource res : resources) {
                try {
                    SkillMeta meta = parseToolMeta(res);
                    Tool tool = instantiate(meta);
                    tools.add(tool);
                    toolByName.put(meta.name(), tool);
                    toolDefs.add(toToolBase(meta));
                    log.info("Tool 已加载: {} -> {}", meta.name(), meta.className());
                } catch (Exception e) {
                    log.warn("跳过工具 {}: {}", res.getFilename(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Tool 扫描失败", e);
        }
    }

    private SkillMeta parseToolMeta(Resource res) throws Exception {
        JsonObject json = gson.fromJson(
                new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8), JsonObject.class);
        return new SkillMeta(
                json.get("name").getAsString(),
                json.get("description").getAsString(),
                json.get("parameters").toString(),
                json.get("class").getAsString(),
                json.has("terminal") && json.get("terminal").getAsBoolean()
        );
    }

    @SuppressWarnings("unchecked")
    private Tool instantiate(SkillMeta meta) throws Exception {
        Class<?> clazz = Class.forName(meta.className());
        return (Tool) clazz.getDeclaredConstructor().newInstance();
    }

    private ToolBase toToolBase(SkillMeta meta) {
        return ToolFunction.builder()
                .function(FunctionDefinition.builder()
                        .name(meta.name())
                        .description(meta.description())
                        .parameters(new JsonParser().parse(meta.parameters()).getAsJsonObject())
                        .build())
                .build();
    }

    // ==================== Skill 加载 ====================

    private void loadSkills() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:skills/*.md");
            for (Resource res : resources) {
                Skill skill = parseSkillMd(res);
                skills.put(skill.getName(), skill);
                log.info("Skill 已加载: {}", skill.getName());
            }
        } catch (Exception e) {
            log.error("Skill 加载失败", e);
        }
    }

    private Skill parseSkillMd(Resource res) throws Exception {
        String raw = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String name = res.getFilename().replace(".skill.md", "");
        String description = "";
        String body = raw;

        // 解析 YAML frontmatter（--- 包围的元数据）
        if (raw.startsWith("---")) {
            String[] parts = raw.split("---", 3);
            if (parts.length >= 3) {
                for (String line : parts[1].split("\\R")) {
                    if (line.startsWith("name:")) {
                        name = line.substring("name:".length()).trim();
                    } else if (line.startsWith("description:")) {
                        description = line.substring("description:".length()).trim();
                    }
                }
                body = parts[2].trim();
            }
        }
        // 从正文第一个标题提取描述（如果 frontmatter 没写）
        if (description.isBlank()) {
            for (String line : body.split("\\R")) {
                if (line.trim().startsWith("#")) {
                    description = line.trim().replaceFirst("^#+", "").trim();
                    break;
                }
            }
        }
        return new Skill(name, description, body);
    }

    // ==================== 查询接口 ====================

    /** Skill 目录文本：注入 system prompt，只含 name + description */
    public String getSkillDirectory() {
        if (skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 可用技能工作流\n");
        for (Skill s : skills.values()) {
            sb.append("- **").append(s.getName()).append("**: ").append(s.getDescription()).append("\n");
        }
        sb.append("需要了解某个技能的详细步骤时，调用 load_skill(name)。\n");
        return sb.toString();
    }

    /** 按名称查找技能正文 */
    public Skill findSkill(String name) {
        return skills.get(name);
    }

        /** 手动注册一个 Tool（适用于需要 Spring DI 的工具，不走 JSON 反射） */
    public void registerTool(Tool tool) {
        String name = tool.getDefinition().getName();
        tools.add(tool);
        toolByName.put(name, tool);
        toolDefs.add((ToolBase) ToolFunction.builder().function(tool.getDefinition()).build());
        log.info("Tool 手动注册: {}", name);
    }

    // ==================== Tool 查询接口 ====================

    public Collection<Skill> getSkills() { return skills.values(); }
    public List<Tool> getTools() { return tools; }
    public List<ToolBase> getToolDefs() { return toolDefs; }
    public Tool getTool(String name) { return toolByName.get(name); }
    public boolean isTerminal(String toolName) {
        Tool t = toolByName.get(toolName);
        return t != null && t.isTerminal();
    }
}
