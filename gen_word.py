#!/usr/bin/env python3
"""生成 Function Calling 学习笔记 Word 文档"""
from docx import Document
from docx.shared import Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
import os

doc = Document()
style = doc.styles['Normal']
style.font.name = 'Arial'
style.font.size = Pt(11)

# 封面
t = doc.add_heading('Function Calling 学习笔记', level=0)
t.alignment = WD_ALIGN_PARAGRAPH.CENTER
s = doc.add_paragraph('从原理到实战，理解 AI 如何调用工具')
s.alignment = WD_ALIGN_PARAGRAPH.CENTER
s = doc.add_paragraph('2026年7月22日 | 阿里云百炼 DashScope SDK + Java')
s.alignment = WD_ALIGN_PARAGRAPH.CENTER
doc.add_page_break()

# 一、原理与工作流
doc.add_heading('一、Function Calling 原理与工作流', level=1)

doc.add_heading('1.1 是什么', level=2)
doc.add_paragraph('Function Calling（函数调用）让 AI 不只说话，还能调用外部工具。开发者给 AI 一个工具箱，AI 自己判断什么时候用哪个、传什么参数。')

doc.add_heading('1.2 工作流（5 步）', level=2)
steps = [
    ('第1步 定义工具', '用 JSON Schema 描述每个工具：名称、功能描述、参数类型。这是 AI 理解工具的说明书。'),
    ('第2步 发送请求', '用户消息 + 工具列表一起发给大模型。'),
    ('第3步 模型决策', '如果模型需要工具，返回 tool_call（工具名+参数）；不需要则直接返回文字。'),
    ('第4步 执行工具', '你的代码根据 tool_call 执行真实业务逻辑（查天气、搜索、生图等）。'),
    ('第5步 返回结果', '工具结果作为新消息发回模型，模型组织最终回复。'),
]
for title, desc in steps:
    p = doc.add_paragraph()
    p.add_run(title + '：').bold = True
    p.add_run(desc)

doc.add_paragraph('')
doc.add_paragraph('关键点：模型不执行工具——它只建议调哪个。真正执行的是你的代码。工具调用可以多轮循环。工具描述的质量决定调用准确率。')

doc.add_page_break()

# 二、API 工作流
doc.add_heading('二、OpenAI / 阿里百炼 Function Calling API 工作流', level=1)

doc.add_heading('2.1 JSON Schema 工具定义', level=2)
doc.add_paragraph('工具用 JSON Schema 定义，核心三要素：name（工具名）、description（功能描述，AI 理解的唯一依据）、parameters（参数定义，含 type/properties/required）。')

doc.add_paragraph('示例（天气工具）：')
doc.add_paragraph('''{
  "type": "function",
  "function": {
    "name": "get_weather",
    "description": "查询某个城市的实时天气",
    "parameters": {
      "type": "object",
      "properties": {
        "city": {"type": "string", "description": "城市名，如：北京"}
      },
      "required": ["city"]
    }
  }
}''')

doc.add_heading('2.2 请求格式', level=2)
doc.add_paragraph('在 Chat Completions API 的请求体中增加 tools 字段：')
doc.add_paragraph('{"model":"qwen-plus","messages":[...],"tools":[...]}')

doc.add_heading('2.3 模型返回 Tool Call', level=2)
doc.add_paragraph('当 finish_reason="tool_calls" 时，message 中包含 tool_calls 数组，每个元素有 id、function.name、function.arguments。')

doc.add_heading('2.4 提交工具结果', level=2)
doc.add_paragraph('执行工具后，把结果作为 role="tool" 的消息追加，然后再次调用 API 让模型生成最终回复。')

doc.add_page_break()

# 三、与传统 API 区别
doc.add_heading('三、Function Calling 与传统 API 的区别', level=1)

doc.add_heading('3.1 传统方式', level=2)
doc.add_paragraph('用户输入 -> 关键词匹配/正则判断意图 -> if/else 路由 -> 调对应 API -> 拼文字返回。核心问题：规则写死，多工具组合调用难。')

doc.add_heading('3.2 Function Calling 方式', level=2)
doc.add_paragraph('用户输入 -> 大模型（带着工具列表） -> AI 自己决定调什么 -> 代码只负责执行 -> AI 组织回复。')

doc.add_heading('3.3 对比表', level=2)
table = doc.add_table(rows=8, cols=3)
table.style = 'Light Grid Accent 1'
for i, h in enumerate(['对比维度', '传统 API', 'Function Calling']):
    table.rows[0].cells[i].text = h
    for r in table.rows[0].cells[i].paragraphs[0].runs:
        r.bold = True

for i, row in enumerate([
    ['意图识别', '开发写 if/else', 'AI 自动判断'],
    ['多工具组合', '难写', 'AI 自动编排'],
    ['扩展工具', '改路由代码', '加一个类，不改主逻辑'],
    ['上下文理解', '手动传', 'AI 利用对话历史'],
    ['多轮调用', '自写循环', 'AI 自循环'],
    ['维护成本', '规则多则乱', '工具独立'],
    ['典型场景', '简单分发', '复杂编排'],
]):
    for j, v in enumerate(row):
        table.rows[i+1].cells[j].text = v

doc.add_heading('3.4 选择建议', level=2)
doc.add_paragraph('用 Function Calling：工具多、需组合、会持续增加。用传统方式：功能少且固定、需毫秒级响应。')

doc.add_page_break()

# 四、实战
doc.add_heading('四、DashScope SDK 实战（项目架构）', level=1)

doc.add_heading('4.1 方案', level=2)
doc.add_paragraph('选阿里 SDK 而非 OpenAI 格式：MultiModalConversation 支持多模态+Function Calling，FunctionDefinition/ToolFunction 封装了 JSON Schema，参数用 Gson JsonObject 类型安全。')

doc.add_heading('4.2 架构', level=2)
doc.add_paragraph('一个工具一个类：')
doc.add_paragraph('''tool/
  Tool.java              -- 接口（getDefinition + execute）
  VoiceConfig.java       -- 共享音色配置
  PendingMediaQueue.java -- 积压图片/语音队列
  impl/
    GetWeatherTool.java       -- 查天气
    GenerateImageTool.java    -- 文生图
    GetRealtimeInfoTool.java  -- 联网搜索
    TextToSpeechTool.java     -- 文字转语音
    ChangeVoiceTool.java      -- 切换音色
    ListVoicesTool.java       -- 列出音色''')

doc.add_paragraph('加新工具只需建类 implements Tool + @Component，Spring 自动扫描，主逻辑一行不改。')

doc.add_heading('4.3 核心代码模式', level=2)
doc.add_paragraph('public interface Tool {')
doc.add_paragraph('    FunctionDefinition getDefinition();  // JSON Schema')
doc.add_paragraph('    String execute(JsonObject args);     // 执行业务逻辑')
doc.add_paragraph('}')

doc.add_heading('4.4 多轮循环', level=2)
doc.add_paragraph('AiSdkChatServiceImpl.chatWithTools() 中 for 循环最多 5 轮：每轮调 API，有 tool_calls 就执行工具并继续，没有就返回文字。')

doc.add_heading('4.5 共享配置', level=2)
doc.add_paragraph('VoiceConfig（音色）和 PendingMediaQueue（积压媒体）是独立的 @Component，注入给需要的工具。AiSdkChatServiceImpl 不碰它们——工具间直接通过共享配置通信。')

# 保存
out = r'D:\Study Materials\Function Calling 学习笔记.docx'
os.makedirs(os.path.dirname(out), exist_ok=True)
doc.save(out)
print('OK: ' + out)
