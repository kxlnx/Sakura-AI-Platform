package com.yupi.yuaiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.yupi.yuaiagent.constant.FileConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Paths;

/**
 * PDF 生成工具
 */
@Slf4j
public class PDFGenerationTool {

    @Tool(description = "Generate a PDF file with given content", returnDirect = false)
    public String generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        // 清理内容中的特殊字符，避免 JSON 解析失败
        if (content != null) {
            content = content.replace("\r\n", "\n")  // 统一换行符
                           .replace("\r", "\n");
        }
        
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        String filePath = fileDir + "/" + fileName;
        try {
            // 创建目录
            FileUtil.mkdir(fileDir);
            // 创建 PdfWriter 和 PdfDocument 对象
            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                
                // 尝试加载中文字体，失败则使用默认字体
                PdfFont font;
                try {
                    font = loadChineseFont();
                    log.info("[PDF生成] 成功加载中文字体");
                } catch (Exception e) {
                    log.warn("[PDF生成] 中文字体加载失败，使用默认字体: {}", e.getMessage());
                    font = null; // 使用默认字体
                }
                
                // 创建段落并设置字体
                Paragraph paragraph = new Paragraph(content);
                if (font != null) {
                    paragraph.setFont(font);
                }
                
                // 添加段落并关闭文档
                document.add(paragraph);
            }
            log.info("[PDF生成] 成功: {}", filePath);
            return "PDF generated successfully to: " + filePath;
        } catch (Exception e) {
            log.error("[PDF生成] 失败: {}", e.getMessage(), e);
            return "Error generating PDF: " + e.getMessage();
        }
    }

    /**
     * 加载中文字体（按优先级尝试多个字体）
     * 如果所有字体都失败，会抛出异常，调用方应该捕获并使用默认字体
     */
    private PdfFont loadChineseFont() throws IOException {
        // 尝试使用 iText 自带的中文字体（需要 font-asian 依赖）
        try {
            log.info("[PDF生成] 尝试加载 STSong-Light 字体");
            return PdfFontFactory.createFont("STSong-Light", "UniGB-UCS2-H", 
                    PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
        } catch (Exception e) {
            log.debug("[PDF生成] STSong-Light 加载失败: {}", e.getMessage());
        }
        
        // 尝试 Windows 系统字体
        String[] fontPaths = {
            "C:\\Windows\\Fonts\\simsun.ttc,0",  // 宋体
            "C:\\Windows\\Fonts\\msyh.ttf",       // 微软雅黑
            "C:\\Windows\\Fonts\\simhei.ttf"      // 黑体
        };
        
        for (String fontPath : fontPaths) {
            try {
                java.io.File fontFile = new java.io.File(fontPath.split(",")[0]);
                if (!fontFile.exists()) {
                    continue;
                }
                
                log.info("[PDF生成] 尝试加载字体: {}", fontPath);
                return PdfFontFactory.createFont(fontPath, "UniGB-UCS2-H",
                        PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
            } catch (Exception e) {
                log.debug("[PDF生成] 字体加载失败: {}", fontPath);
            }
        }
        
        // 所有字体都失败，抛出异常
        throw new IOException("无法加载任何中文字体");
    }
}
