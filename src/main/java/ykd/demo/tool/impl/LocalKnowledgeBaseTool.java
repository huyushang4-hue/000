package ykd.demo.tool.impl;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.utils.Constants;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ykd.demo.tool.Tool;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LocalKnowledgeBaseTool implements Tool {

    private final String apiKey = System.getenv("DASHSCOPE_API_KEY");

    @Value("${local-kb.pdf-path:kb.pdf}")
    private String pdfPath;

    @Value("${local-kb.chunk-size:500}")
    private int chunkSize;

    @Value("${local-kb.chunk-overlap:50}")
    private int chunkOverlap;

    @Value("${local-kb.api-host:ws-bqpdh32m3j65gwt5.cn-beijing.maas.aliyuncs.com}")
    private String apiHost;

    private List<float[]> chunkVectors;
    private List<String> chunks;
    private boolean initialized = false;

    @Override
    public FunctionDefinition getDefinition() {
        return func("query_local_kb",
                "查询本地知识库PDF文档，用于询问培养方案、课程信息、课程安排、教学计划等与学校或专业相关的问题。当用户问到培养方案、课程设置、课程安排等问题时，应优先使用此工具。",
                """
                {"type":"object","properties":{"question":{"type":"string","description":"用户的问题，例如：人工智能有哪些课、培养方案是什么"}},"required":["question"]}
                """);
    }

    @Override
    public String execute(JsonObject args) {
        String question = args.get("question").getAsString();
        log.info("Tool: query_local_kb, question={}", question);

        if (question == null || question.isBlank()) {
            return "错误：请输入要查询的问题";
        }

        if (apiKey == null || apiKey.isBlank()) {
            return "错误：未配置 DASHSCOPE_API_KEY 环境变量";
        }

        try {
            if (!initialized) {
                initKnowledgeBase();
            }

            float[] queryVector = embedText(question);
            List<Map.Entry<Integer, Double>> scored = new ArrayList<>();
            for (int i = 0; i < chunkVectors.size(); i++) {
                double sim = cosineSimilarity(queryVector, chunkVectors.get(i));
                scored.add(Map.entry(i, sim));
            }
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            int topK = Math.min(3, scored.size());
            StringBuilder sb = new StringBuilder();
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < topK; i++) {
                int idx = scored.get(i).getKey();
                String text = chunks.get(idx);
                if (seen.add(text)) {
                    if (sb.length() > 0) sb.append("\n\n---\n\n");
                    sb.append(text);
                }
            }

            if (sb.isEmpty()) {
                return "知识库中未找到相关内容";
            }

            log.info("本地知识库检索完成，返回 {} 个相关片段", seen.size());
            return sb.toString();

        } catch (Exception e) {
            log.error("本地知识库调用失败", e);
            return "错误：知识库服务调用失败 - " + e.getMessage();
        }
    }

    private synchronized void initKnowledgeBase() {
        if (initialized) return;

        long maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        log.info("JVM最大堆内存: {}MB", maxMem);

        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            log.error("PDF文件不存在: {}", pdfFile.getAbsolutePath());
            throw new RuntimeException("PDF文件不存在: " + pdfPath + "，请将PDF放到项目根目录");
        }

        log.info("开始加载本地知识库: {}", pdfFile.getAbsolutePath());

        try {
            String fullText = extractPdfText(pdfFile);
            log.info("PDF文本提取完成，共 {} 字符", fullText.length());

            chunkVectors = new ArrayList<>();
            chunks = new ArrayList<>();

            int len = fullText.length();
            int start = 0;
            int chunkIdx = 0;
            int estimatedChunks = len / (chunkSize - chunkOverlap) + 1;

            while (start < len) {
                int s = start;
                while (s < len && Character.isWhitespace(fullText.charAt(s))) s++;
                if (s >= len) break;

                int end = Math.min(s + chunkSize, len);
                if (end < len) {
                    int lastSpace = fullText.lastIndexOf(' ', end);
                    if (lastSpace > s + chunkSize / 2) {
                        end = lastSpace;
                    }
                }
                int e = end;
                while (e > s && Character.isWhitespace(fullText.charAt(e - 1))) e--;

                if (e > s) {
                    String chunk = fullText.substring(s, e);
                    chunks.add(chunk);

                    float[] vec = embedText(chunk);
                    chunkVectors.add(vec);
                    chunkIdx++;

                    if (chunkIdx % 10 == 0) {
                        log.info("已处理 {}/{} 块", chunkIdx, estimatedChunks);
                    }
                }

                int nextStart = e - chunkOverlap;
                start = Math.max(nextStart, s + 1);
                if (start >= len) break;
            }

            initialized = true;
            log.info("本地知识库初始化完成，共 {} 个文本块", chunks.size());

        } catch (Exception e) {
            log.error("知识库初始化失败", e);
            throw new RuntimeException("知识库初始化失败: " + e.getMessage(), e);
        }
    }

    private String extractPdfText(File pdfFile) throws Exception {
        try (var doc = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private List<String> splitText(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        int len = text.length();

        int start = 0;
        while (start < len) {
            int s = start;
            while (s < len && Character.isWhitespace(text.charAt(s))) s++;
            if (s >= len) break;

            int end = Math.min(s + size, len);
            if (end < len) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > s + size / 2) {
                    end = lastSpace;
                }
            }
            int e = end;
            while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;

            if (e > s) {
                result.add(text.substring(s, e));
            }
            start = e - overlap;
            if (start <= 0) break;
        }
        return result;
    }

    private float[] embedText(String text) throws ApiException, NoApiKeyException {
        Constants.baseHttpApiUrl = "https://" + apiHost + "/api/v1";
        TextEmbeddingParam param = TextEmbeddingParam.builder()
                .model("text-embedding-v3")
                .texts(Collections.singletonList(text))
                .build();
        TextEmbeddingResult result = new TextEmbedding().call(param);
        return toFloatArray(result.getOutput().getEmbeddings().get(0).getEmbedding());
    }

    private List<float[]> batchEmbed(List<String> texts) throws ApiException, NoApiKeyException {
        Constants.baseHttpApiUrl = "https://" + apiHost + "/api/v1";
        TextEmbeddingParam param = TextEmbeddingParam.builder()
                .model("text-embedding-v3")
                .texts(texts)
                .build();
        TextEmbeddingResult result = new TextEmbedding().call(param);
        return result.getOutput().getEmbeddings().stream()
                .map(e -> toFloatArray(e.getEmbedding()))
                .collect(Collectors.toList());
    }

    private float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i).floatValue();
        }
        return arr;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
