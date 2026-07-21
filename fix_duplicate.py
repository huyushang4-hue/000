import re

filepath = r"D:\ykd-spring\demo\src\main\java\ykd\demo\service\impl\AiSdkChatServiceImpl.java"

with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. buildTools() 里删掉重复的 ttsDef 定义（第 124~132 行附近）
# 特征：第一个 ttsDef 是 buildTools 里正常定义 → 保留
#       第二个 ttsDef 是重复的 → 删掉
# 找到 "FunctionDefinition ttsDef" 第二次出现的位置
idx = content.find("FunctionDefinition ttsDef")
if idx != -1:
    idx2 = content.find("FunctionDefinition ttsDef", idx + 10)
    if idx2 != -1:
        # 从 idx2 往前找到行首，往后找到第一个 .build(); 之后的行尾
        line_start = content.rfind('\n', 0, idx2) + 1
        # 找到这个定义结束的位置（下一个 FunctionDefinition 或 return Arrays.asList）
        end_marker = content.find('\n                .name(', idx2)
        end_line = content.find('\n', end_marker + 1)
        # 往后找到 .build() 那行的结尾
        build_end = content.find('.build();', end_line)
        if build_end != -1:
            line_end = content.find('\n', build_end) + 1
            # 还要删掉下面多余的 ToolFunction.builder().function(ttsDef).build()
            tool_line = content.find('ToolFunction.builder().function(ttsDef).build()', line_end)
            if tool_line != -1:
                tool_end = content.find('\n', tool_line) + 1
                deleted_section = content[line_start:tool_end]
                content = content[:line_start] + content[tool_end:]
                print(f"deleted duplicate ttsDef, {len(deleted_section)} chars removed")

# 2. executeToolCall() 里删掉重复的 text_to_speech case（第二个）
# 找到第二个 "case \"text_to_speech\""
idx = content.find('case "text_to_speech"')
if idx != -1:
    idx2 = content.find('case "text_to_speech"', idx + 10)
    if idx2 != -1:
        # 从这个 case 的行首删到下一个 case 或 default 的前一行
        case_start = content.rfind('\n', 0, idx2) + 1
        # 找到下一个 case 或 default
        next_case = content.find('\n                case "', case_start)
        if next_case == -1:
            next_case = content.find('\n                default ->', case_start)
        if next_case != -1:
            # 还要把前面的逗号删掉
            # 上一个 case 结尾的逗号在 case_start 前面几行
            # 直接删从 case_start 到 next_case
            deleted_case = content[case_start:next_case]
            content = content[:case_start] + content[next_case:]
            print(f"deleted duplicate text_to_speech case, {len(deleted_case)} chars removed")

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)

print("done")