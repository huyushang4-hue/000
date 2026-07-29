---
name: image-generation
description: 图片生成——根据用户描述生成图片，完成后不发文字
---

# 图片生成工作流

当用户要求生成/画/制作图片时：

1. **理解需求**：将用户的中文描述转换为适合 AI 绘图的英文 prompt
2. **生成图片**：调用 `generate_image`，传入英文 prompt
3. **完成**：图片发送后直接结束，**不需要再回复文字**

注意事项：
- prompt 要详细描述风格、内容、色调
- 可以用"cute"、"fluffy"、"cinematic lighting"等修饰词提升效果
- generate_image 是终端工具，执行完对话结束
