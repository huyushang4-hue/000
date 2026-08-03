---
name: video-search
description: 视频搜索——自动识别平台（B站/YouTube/抖音），全平台搜索或按需限定，返回标题、作者、播放量、时长、链接
---

# 视频搜索工作流

当用户想找视频（说"搜视频/找视频/B站搜XX/看看XX视频"等）时，按以下步骤执行：

## 执行步骤
1. 判断用户是否指定了平台：
   - B站/bilibili/哔哩哔哩 → platform=bilibili
   - YouTube/油管 → platform=youtube
   - 抖音/douyin → platform=douyin
2. 指定了平台 → 调 `search_video` 工具并传入 platform 参数限定平台
3. 没指定平台 → 调 `search_video` 工具，platform 传 "all" 全平台搜索
4. 如果搜索结果太少，可以换关键词再搜一次
5. 将结果以友好的格式展示：标题、UP主/频道、播放量、时长、链接

直接输出结果，不要反问用户其他信息。
