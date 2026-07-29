package ykd.demo.skill;

import ykd.demo.tool.Tool;

/**
 * Skill 元数据：从 skills/*.json 解析得到
 * 禁止 if-else 硬编码，完全由配置文件驱动
 */
public record SkillMeta(
    String name,
    String description,
    String parameters,
    String className,
    boolean terminal
) {}
