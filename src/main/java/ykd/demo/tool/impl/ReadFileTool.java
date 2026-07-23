package ykd.demo.tool.impl;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import ykd.demo.tool.AttachmentStore;
import ykd.demo.tool.Tool;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** AI 读取本地/远程文件：PDF、Word、Excel、文本 */
@Slf4j
@Component
public class ReadFileTool implements Tool {

    private static final int MAX_CHARS = 3000;
    private static final Tika TIKA = new Tika();
    private final AttachmentStore attachmentStore;

    public ReadFileTool(AttachmentStore attachmentStore) {
        this.attachmentStore = attachmentStore;
    }

    @Override
    public FunctionDefinition getDefinition() {
        return func("read_file", """
            读取文件内容，支持 PDF、Word(docx)、Excel(xlsx)、文本文件(txt/md/html)。
            传入 URL、本地路径，或传入 **attachment_store** 读取微信用户刚发的文件。
            返回提取的文字（最长 3000 字）。
            """,
            """
            {"type":"object","properties":{"path":{"type":"string","description":"文件 URL、本地路径，或 attachment_store（读取微信用户刚发的文档）"}},"required":["path"]}
            """);
    }

    @Override
    public String execute(JsonObject args) {
        String path = args.get("path").getAsString();
        log.info("Tool: read_file, path={}", path);

        try {
            byte[] bytes = readBytes(path);
            String mime = TIKA.detect(bytes);
            log.info("检测到文件类型: {}", mime);

            String text = extractText(bytes, mime);
            if (text.length() > MAX_CHARS)
                text = text.substring(0, MAX_CHARS) + "\n\n[... 已截断，共 " + text.length() + " 字符]";
            return text;

        } catch (Exception e) {
            log.error("read_file 失败", e);
            return "文件读取失败：" + e.getMessage();
        }
    }

    private byte[] readBytes(String path) throws Exception {
        // attachment_store → 从 AttachmentStore 读取微信用户发的文件
        if ("attachment_store".equals(path)) {
            String base64 = attachmentStore.getBase64();
            if (base64 == null || base64.isBlank())
                throw new RuntimeException("AttachmentStore 中没有暂存的文件");
            return Base64.getDecoder().decode(base64);
        }
        try { return new URL(path).openStream().readAllBytes(); }
        catch (Exception e) {
            log.debug("URL 读取失败，尝试本地路径: {}", path);
            return Files.readAllBytes(Path.of(path));
        }
    }

    private String extractText(byte[] bytes, String mime) throws Exception {
        if (mime.startsWith("image/")) return "[图片文件，无法提取文字]";
        if (mime.startsWith("audio/")) return "[音频文件，无法提取文字]";
        if (mime.startsWith("video/")) return "[视频文件，无法提取文字]";

        // PDF
        if (mime.contains("pdf")) return extractPdf(bytes);

        // Word (docx) — 匹配标准 MIME 和 Tika 通用 MIME
        if (mime.contains("ooxml") || mime.contains("wordprocessingml") || mime.contains("opendocument.text"))
            return extractDocx(bytes);

        // Excel (xlsx)
        if (mime.contains("spreadsheetml") || mime.contains("ms-excel"))
            return extractXlsx(bytes);

        // 纯文本
        if (mime.startsWith("text/")) return new String(bytes, StandardCharsets.UTF_8);

        return "[不支持的文件格式: " + mime + "]";
    }

    private String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String extractDocx(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append('\n'));
            return sb.toString();
        }
    }

    private String extractXlsx(byte[] bytes) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            wb.forEach(sheet -> {
                sb.append("=== ").append(sheet.getSheetName()).append(" ===\n");
                sheet.forEach(row -> row.forEach(cell -> sb.append(cell.toString()).append('\t')));
                sb.append('\n');
            });
            return sb.toString();
        }
    }
}