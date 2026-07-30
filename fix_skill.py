with open('src/main/java/ykd/demo/skill/SkillManager.java','r',encoding='utf-8') as f: c=f.read()
old='    public Skill findSkill'
new='    public String getSkillDirectory() {\n        if (skills.isEmpty()) return "";\n        StringBuilder sb = new StringBuilder();\n        sb.append("\\n## \\u53ef\\u7528\\u6280\\u80fd\\n");\n        for (Skill s : skills.values()) sb.append("- ").append(s.getName()).append(": ").append(s.getDescription()).append("\\n");\n        sb.append("\\u9700\\u8981\\u4e86\\u89e3\\u7ec6\\u8282\\u65f6\\u8c03\\u7528 load_skill(name)\\u3002\\n");\n        return sb.toString();\n    }\n\n    public Skill findSkill'
c=c.replace(old,new)
with open('src/main/java/ykd/demo/skill/SkillManager.java','w',encoding='utf-8') as f: f.write(c)
