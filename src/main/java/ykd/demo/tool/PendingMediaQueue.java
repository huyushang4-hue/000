package ykd.demo.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 积压媒体队列：工具生成的图片/WAV 暂存此处，发完文字后统一发送 */
@Slf4j
@Component
public class PendingMediaQueue {
    private final List<Map.Entry<byte[], String>> queue = Collections.synchronizedList(new ArrayList<>());

    public void add(byte[] bytes, String fileName) {
        queue.add(new java.util.AbstractMap.SimpleEntry<>(bytes, fileName));
        log.info("积压队列 +1: {}", fileName);
    }

    public boolean hasPending() { return !queue.isEmpty(); }
    public List<Map.Entry<byte[], String>> drain() {
        List<Map.Entry<byte[], String>> pending = new ArrayList<>(queue);
        queue.clear();
        return pending;
    }
}
