package ykd.demo.skill;

/**
 * 技能数据对象：对应 skills/*.md 的解析结果
 *
 * Skill ≠ Tool：Skill 是纯知识/工作流提示词，Tool 是可执行函数。
 * 启动时只把 name/description 注入 system prompt（目录），
 * 正文 body 等 AI 调用 load_skill 时才返回（按需加载）。
 */
public class Skill {

    private final String name;
    private final String description;
    private final String body;

    public Skill(String name, String description, String body) {
        this.name = name;
        this.description = description;
        this.body = body;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getBody() { return body; }
}
