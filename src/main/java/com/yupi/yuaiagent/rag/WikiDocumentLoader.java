package com.yupi.yuaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wikipedia 文档加载器
 * 支持 Wikiextractor 输出的 MediaWiki 标题语法（== / ===），
 * 按标题层级切分，附带回溯路径元数据。
 */
@Slf4j
@Component
public class WikiDocumentLoader {

    @Value("${sakura.wiki.dir:E:/tools/milvus/wikimd}")
    private String wikiDir;

    @Value("${sakura.wiki.chunk-size:1200}")
    private int maxChunkChars;

    public List<Document> loadWikiDocuments() {
        List<Document> allDocs = new ArrayList<>();
        File dir = new File(wikiDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("[Wiki] 目录不存在: {}", wikiDir);
            return allDocs;
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".md"));
        if (files == null) return allDocs;

        log.info("[Wiki] 发现 {} 篇文档", files.length);
        int loaded = 0;
        for (File file : files) {
            try {
                String title = extractTitle(file.getName());
                List<Document> chunks = readAndSplit(file, title);
                allDocs.addAll(chunks);
                loaded++;
                if (loaded % 100 == 0) log.info("[Wiki] 已加载 {}/{} 篇...", loaded, files.length);
            } catch (Exception e) {
                log.warn("[Wiki] 加载失败: {} -> {}", file.getName(), e.getMessage());
            }
        }
        log.info("[Wiki] 加载完成，{} 篇 → {} 个切片", files.length, allDocs.size());
        return allDocs;
    }

    private String extractTitle(String name) {
        String n = name.replace(".md", "");
        int i = n.indexOf('_');
        return i > 0 ? n.substring(i + 1) : n;
    }

    // ========== 切分逻辑 ==========

    /** 去掉文末参考资料/参见/外部链接 */
    private String cutAtReferences(String content) {
        for (String m : new String[]{"\n== 参考资料", "\n== 相关文献", "\n== 参见",
                "\n== 外部链接", "\n== 注释", "\n== 参考来源", "\n== 参考文献",
                "\n== References", "\n== See also"}) {
            int idx = content.indexOf(m);
            if (idx > 0) return content.substring(0, idx);
        }
        return content;
    }

    /**
     * 切分策略：按 == 一级标题切大段 → 大段超长时按 === 二级标题或自然段再切
     */
    private List<Document> readAndSplit(File file, String title) throws IOException {
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        content = cutAtReferences(content);
        List<Document> result = new ArrayList<>();

        // 按 == 一级标题切
        String[] sections = content.split("\n(?=[=]{2,3}\\s)");
        int ci = 0;

        for (String sec : sections) {
            String text = sec.trim();
            if (text.length() < 30) continue;

            String h = "";
            String[] lines = text.split("\n", 2);
            if (lines[0].matches("={2,3}\\s.*?\\s={2,3}")) {
                h = lines[0];
                text = lines.length > 1 ? lines[1].trim() : "";
                if (text.length() < 30) continue;
            }

            if (text.length() <= maxChunkChars) {
                result.add(makeDoc(h.isEmpty() ? text : h + "\n" + text, title, ci++, file.getName()));
            } else {
                // 长段：继续按 === 或自然段切
                String[] subs = text.split("\n(?=[=]{2,3}\\s)");
                for (String sub : subs) {
                    int before = result.size();
                    flatten(sub, h, title, before, file.getName(), result);
                    ci = result.size();
                }
            }
        }
        return result;
    }

    private void flatten(String text, String parentH, String title,
                         int idx, String filename, List<Document> out) {
        text = text.trim();
        if (text.length() < 30) return;

        // 本段标题
        String localH = "";
        String[] lines = text.split("\n", 2);
        if (lines[0].matches("={2,3}\\s.*?\\s={2,3}")) {
            localH = lines[0];
            text = lines.length > 1 ? lines[1].trim() : "";
        }
        String prefix = localH.isEmpty() ? parentH : (parentH.isEmpty() ? localH : parentH + " > " + localH);

        if (text.length() <= maxChunkChars) {
            out.add(makeDoc(prefix.isEmpty() ? text : prefix + "\n" + text, title, idx, filename));
        } else {
            // 按自然段切
            String[] paras = text.split("\n");
            StringBuilder buf = new StringBuilder();
            for (String p : paras) {
                String t = p.trim();
                if (t.isEmpty() || t.matches("={2,3}\\s.*")) continue;
                if (buf.length() + t.length() > maxChunkChars && buf.length() > 200) {
                    out.add(makeDoc(prefix.isEmpty() ? buf.toString() : prefix + "\n" + buf, title, idx, filename));
                    buf.setLength(0);
                }
                if (buf.length() > 0) buf.append("\n");
                buf.append(t);
            }
            if (buf.length() > 30) {
                out.add(makeDoc(prefix.isEmpty() ? buf.toString() : prefix + "\n" + buf, title, idx, filename));
            }
        }
    }

    private Document makeDoc(String text, String title, int idx, String filename) {
        return new Document(text, Map.of(
                "title", title,
                "source", "wiki",
                "filename", filename,
                "chunk_index", String.valueOf(idx)));
    }
}
