import re

with open('/d/ykd-spring/demo/src/main/java/ykd/demo/service/impl/AiSdkChatServiceImpl.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = '''                case "generate_image" -> {
                    String prompt = args.get("prompt").getAsString();
                    log.info("Function Calling → 文生图，prompt={}", prompt);
                    yield generateImage(prompt);
                }'''

new = '''                case "generate_image" -> {
                    String prompt = args.get("prompt").getAsString();
                    log.info("Function Calling → 文生图，prompt={}", prompt);
                    String imageUrl = generateImage(prompt);
                    if (imageUrl.startsWith("http")) {
                        try {
                            byte[] imgBytes = RestClient.create().get()
                                    .uri(imageUrl)
                                    .retrieve()
                                    .body(byte[].class);
                            if (imgBytes != null && imgBytes.length > 0) {
                                pendingImages.add(new java.util.AbstractMap.SimpleEntry<>(imgBytes, "generated.png"));
                            }
                        } catch (Exception ex) {
                            log.warn("下载生成图片失败", ex);
                        }
                    }
                    yield "图片已生成，请在回复中告知用户图片已发送。";
                }'''

if old in content:
    content = content.replace(old, new)
    with open('/d/ykd-spring/demo/src/main/java/ykd/demo/service/impl/AiSdkChatServiceImpl.java', 'w', encoding='utf-8') as f:
        f.write(content)
    print('replace ok')
else:
    print('old not found')
    # find similar lines
    import difflib
    lines = content.split('\\n')
    for i, line in enumerate(lines):
        if 'generate_image' in line:
            print(f'{i+1}: {line}')