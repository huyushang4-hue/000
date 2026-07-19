# -*- coding: utf-8 -*-
import os, sys
sys.stdout.reconfigure(encoding='utf-8')

os.makedirs('D:/Study Materials', exist_ok=True)

from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

doc = Document()

style = doc.styles['Normal']
font = style.font
font.name = 'Microsoft YaHei'
font.size = Pt(10.5)
style.element.rPr.rFonts.set(qn('w:eastAsia'), 'Microsoft YaHei')

for s in doc.sections:
    s.top_margin = Cm(2.5)
    s.bottom_margin = Cm(2.5)
    s.left_margin = Cm(2.5)
    s.right_margin = Cm(2.5)

def heading(text, level):
    hd = doc.add_heading(text, level=level)
    for r in hd.runs:
        r.font.name = 'Microsoft YaHei'
        r.element.rPr.rFonts.set(qn('w:eastAsia'), 'Microsoft YaHei')
    return hd

def para(text, bold=False, size=10.5, color=None, align=None):
    p = doc.add_paragraph()
    if align: p.alignment = align
    run = p.add_run(text)
    run.font.name = 'Microsoft YaHei'
    run.font.size = Pt(size)
    run.bold = bold
    if color: run.font.color.rgb = color
    p.paragraph_format.space_after = Pt(4)
    return p

def code(text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(4)
    p.paragraph_format.space_after = Pt(4)
    p.paragraph_format.left_indent = Cm(0.5)
    pPr = p._p.get_or_add_pPr()
    shd = OxmlElement('w:shd')
    shd.set(qn('w:fill'), 'F5F5F5')
    shd.set(qn('w:val'), 'clear')
    pPr.append(shd)
    run = p.add_run(text)
    run.font.name = 'Consolas'
    run.font.size = Pt(8.5)
    run.font.color.rgb = RGBColor(0x1E, 0x1E, 0x1E)
    return p

def note(text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(2)
    p.paragraph_format.space_after = Pt(2)
    p.paragraph_format.left_indent = Cm(0.3)
    run = p.add_run('\U0001f4a1 ' + text)
    run.font.size = Pt(9)
    run.font.color.rgb = RGBColor(0x66, 0x66, 0x66)
    return p

# ===== Cover =====
for _ in range(5): doc.add_paragraph()
para('Spring Boot 项目开发笔记', bold=True, size=24, color=RGBColor(0x2B, 0x57, 0x9A), align=WD_ALIGN_PARAGRAPH.CENTER)
para('ykd.demo 项目实战知识手册', size=13, color=RGBColor(0x66, 0x66, 0x66), align=WD_ALIGN_PARAGRAPH.CENTER)
doc.add_paragraph()
para('持续更新中...', size=11, color=RGBColor(0x99, 0x99, 0x99), align=WD_ALIGN_PARAGRAPH.CENTER)
para('当前章节：RestClient HTTP 请求', size=10, color=RGBColor(0xBB, 0xBB, 0xBB), align=WD_ALIGN_PARAGRAPH.CENTER)
doc.add_page_break()

# ===== TOC =====
heading('目  录', 1)
doc.add_paragraph()
para('第一篇  RestClient HTTP 请求', bold=True, size=11, color=RGBColor(0x2B, 0x57, 0x9A))
toc = [
    '    1.1  六步请求模板',
    '    1.2  代码详解',
    '    1.3  请求方式',
    '    1.4  实战：查询天气（GET）',
    '    1.5  实战：调用大模型（POST）',
    '    1.6  实战：联网搜索（POST）',
    '    1.7  实战：下载文件（GET）',
    '    1.8  常见问题',
]
for item in toc:
    p = doc.add_paragraph()
    r = p.add_run(item)
    r.font.name = 'Microsoft YaHei'; r.font.size = Pt(10)
    r.font.color.rgb = RGBColor(0x55, 0x55, 0x55)
doc.add_paragraph()
doc.add_paragraph()
para('第二篇  ~ 待添加 ~', bold=True, size=11, color=RGBColor(0xCC, 0xCC, 0xCC))
p = doc.add_paragraph()
r = p.add_run('    2.1  ~ 待添加 ~')
r.font.name = 'Microsoft YaHei'; r.font.size = Pt(10)
r.font.color.rgb = RGBColor(0xCC, 0xCC, 0xCC)
doc.add_page_break()

# ===== Content =====
heading('第一篇  RestClient HTTP 请求', 1)
para('Spring Boot 4.1 内置的 RestClient 是项目中最通用的 HTTP 工具。无论调用哪个外部 API，代码结构完全一致，只需更换 URL 和请求参数。')

heading('1.1  六步请求模板', 2)
para('以下六行代码是所有 POST 请求的标准结构，所有 HTTP 请求都可以套用：')

code1 = (
    'String responseJson = restClient.post()      // ① 发起 POST 请求\n'
    '    .uri("https://api.deepseek.com/chat/completions")  // ② 目标地址\n'
    '    .header("Content-Type", "application/json")        // ③ 请求头（格式声明）\n'
    '    .header("Authorization", "Bearer " + apiKey)        // ③ 请求头（身份认证）\n'
    '    .body(requestBody)                                  // ④ 请求体\n'
    '    .retrieve()                                         // ⑤ 执行请求\n'
    '    .body(String.class);                                // ⑥ 拿响应'
)
code(code1)

para('GET 请求更简单——没有 .body()，参数拼在 URL 中：')
code_get = (
    'String json = restClient.get()\n'
    '    .uri("https://restapi.amap.com/v3/weather/weatherInfo?city={city}&key={key}",\n'
    '         city, apiKey)\n'
    '    .retrieve()\n'
    '    .body(String.class);'
)
code(code_get)
note('核心理念：所有 HTTP API 都是这套六步结构。换了地址和参数，结构完全不变。')
note('响应体类型：.body(String.class) 拿字符串；.body(byte[].class) 拿字节（图片/音频）。')

heading('1.2  代码详解', 2)
t = doc.add_table(rows=7, cols=3)
t.style = 'Light Shading Accent 1'
for i, h in enumerate(['代码行', '作用', '类比']):
    c = t.rows[0].cells[i]; c.text = h
    for p in c.paragraphs:
        for r in p.runs: r.bold = True; r.font.name = 'Microsoft YaHei'; r.font.size = Pt(9)
rows = [
    ('restClient.post()', '发起 POST 请求', '拿起快递单'),
    ('.uri("地址")', '填写目标 URL', '写收件地址'),
    ('.header("Content-Type")', '告诉数据格式', '贴标签'),
    ('.header("Authorization")', '身份认证', '贴邮票'),
    ('.body(requestBody)', '发送的数据', '装快递箱'),
    ('.retrieve().body()', '执行并拿响应', '寄出→收信→拆开'),
]
for idx, (c1, c2, c3) in enumerate(rows):
    row = t.rows[idx + 1]
    for j, v in enumerate([c1, c2, c3]):
        c = row.cells[j]; c.text = v
        for p in c.paragraphs:
            for r in p.runs: r.font.name = 'Microsoft YaHei'; r.font.size = Pt(8.5)
doc.add_paragraph()

heading('1.3  请求方式', 2)
para('HTTP 定义了五种常用请求方式：')
t2 = doc.add_table(rows=6, cols=3)
t2.style = 'Light Shading Accent 1'
for i, h in enumerate(['方式', '用法', '项目应用']):
    c = t2.rows[0].cells[i]; c.text = h
    for p in c.paragraphs:
        for r in p.runs: r.bold = True; r.font.name = 'Microsoft YaHei'; r.font.size = Pt(9)
rows2 = [
    ('GET', '获取数据', '下载图片/音频、查天气'),
    ('POST', '发送数据等回复', '调 AI、搜索、生图、TTS'),
    ('PUT', '全量替换', '暂未使用'),
    ('DELETE', '删除', '暂未使用'),
    ('PATCH', '局部更新', '暂未使用'),
]
for idx, (c1, c2, c3) in enumerate(rows2):
    row = t2.rows[idx + 1]
    for j, v in enumerate([c1, c2, c3]):
        c = row.cells[j]; c.text = v
        for p in c.paragraphs:
            for r in p.runs: r.font.name = 'Microsoft YaHei'; r.font.size = Pt(8.5)
note('项目 90% 请求用 GET 和 POST。')
doc.add_page_break()

heading('1.4  实战：查询天气（GET 请求）', 2)
para('调用高德天气 API：')
cc1 = (
    'String json = restClient.get()\n'
    '    .uri("https://restapi.amap.com/v3/weather/weatherInfo?city={city}&key={key}",\n'
    '         city, apiKey)\n'
    '    .retrieve()\n'
    '    .body(String.class);\n'
    '\n'
    '// 解析 JSON\n'
    'JsonNode root = mapper.readTree(json);\n'
    'String status = root.get("status").asText();\n'
    'String weather = root.get("lives").get(0).get("weather").asText();'
)
code(cc1)
note('GET 没有 .body()。参数用 {} 占位拼在 URL 中。')

heading('1.5  实战：调用大模型（POST 请求）', 2)
para('调用 DeepSeek / 千问：')
cc2 = (
    '// ① 拼请求体（%s 占位，formatted() 替换）\n'
    'String requestBody = """\n'
    '{\n'
    '    "model": "deepseek-v4-flash",\n'
    '    "messages": [\n'
    '        {"role": "system", "content": "..."},\n'
    '        {"role": "user", "content": "%s"}\n'
    '    ]\n'
    '}\n'
    '""".formatted(input.replace("\\"", "\\\\""));\n'
    '\n'
    '// ② 发 POST\n'
    'String responseJson = restClient.post()\n'
    '    .uri("https://api.deepseek.com/chat/completions")\n'
    '    .header("Content-Type", "application/json")\n'
    '    .header("Authorization", "Bearer " + apiKey)\n'
    '    .body(requestBody)\n'
    '    .retrieve()\n'
    '    .body(String.class);\n'
    '\n'
    '// ③ 解析\n'
    'JsonNode root = mapper.readTree(responseJson);\n'
    'String result = root.path("choices").get(0)\n'
    '    .path("message").path("content").asText();'
)
code(cc2)
note('%s 是占位符，formatted() 填用户输入。输入中的引号要转义。')
note('认证方式：Bearer Token 放 Authorization Header。')

heading('1.6  实战：联网搜索（POST 请求）', 2)
para('调用 Tavily 搜索 API：')
cc3 = (
    'String requestBody = String.format("""\n'
    '{\n'
    '    "api_key": "%s",\n'
    '    "query": "%s",\n'
    '    "search_depth": "basic",\n'
    '    "include_answer": true,\n'
    '    "max_results": 5\n'
    '}\n'
    '""", apiKey, query.replace("\\"", "\\\\""));\n'
    '\n'
    'String responseJson = restClient.post()\n'
    '    .uri("https://api.tavily.com/search")\n'
    '    .header("Content-Type", "application/json")\n'
    '    .body(requestBody)\n'
    '    .retrieve()\n'
    '    .body(String.class);\n'
    '\n'
    'JsonNode root = mapper.readTree(responseJson);\n'
    'String answer = root.path("answer").asText();'
)
code(cc3)
note('Tavily 把 Key 放请求体内，DeepSeek 放 Header。调新 API 先看文档。')

heading('1.7  实战：下载文件（GET 请求）', 2)
para('.body() 传 byte[].class 拿字节数据：')
cc4 = (
    '// 方式一：RestClient\n'
    'byte[] imageBytes = restClient.get()\n'
    '    .uri(URI.create(imageUrl))\n'
    '    .retrieve()\n'
    '    .body(byte[].class);\n'
    '\n'
    '// 方式二：URL.openStream()（推荐）\n'
    'try (InputStream in = new URL(audioUrl).openStream()) {\n'
    '    byte[] audioBytes = in.readAllBytes();\n'
    '}'
)
code(cc4)
note('下载 OSS 签名文件用 URL.openStream()，RestClient 可能对 URL 二次编码导致 403。')
doc.add_page_break()

heading('1.8  常见问题', 2)

heading('Q1: API Key 无效（401 Unauthorized）', 3)
para('原因：环境变量未配置或 Key 无效。排查：')
para('  ① IDEA → Run Configuration → Environment variables 检查')
para('  ② cmd 运行 echo %变量名% 确认存在')
para('  ③ 去官网重新创建 Key')

heading('Q2: JSON 解析失败', 3)
para('先打印 responseJson 看实际结构。')
cc5 = (
    'root.path("field").asText()    // 安全，字段空返回空串\n'
    'root.get("field").asText()      // 直接，字段空抛异常\n'
    'root.path("a").get(0).path("b") // 链式取数组字段'
)
code(cc5)

heading('Q3: OSS 签名失败（403）', 3)
para('RestClient 对 URL 二次编码。改用 URL.openStream()。')

heading('Q4: 请求体引号问题', 3)
para('用户输入的双引号会破坏 JSON。用 .replace("\\"", "\\\\"") 转义。')
code('input.replace("\\"", "\\\\"");  // 把 " 替换为 \\"')

# Save
fp = 'D:/Study Materials/Spring Boot 项目开发笔记.docx'
doc.save(fp)
print('Done:', fp)
print('Size:', os.path.getsize(fp) / 1024, 'KB')