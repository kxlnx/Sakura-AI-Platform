package com.yupi.yuaiagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.yupi.yuaiagent.constant.FileConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;

/**
 * 资源下载工具
 */

@Slf4j
public class ResourceDownloadTool {

    @Tool(description = "Download a resource from a given URL")
    public String downloadResource(@ToolParam(description = "URL of the resource to download") String url, @ToolParam(description = "Name of the file to save the downloaded resource") String fileName) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/download";
        String filePath = fileDir + "/" + fileName;
        try {
            // 创建目录
            FileUtil.mkdir(fileDir);
            // 设置超时时间：连接超时 5 秒，读取超时 10 秒
            HttpUtil.downloadFile(url, new File(filePath), 10000);
            return "Resource downloaded successfully to: " + filePath;
        } catch (Exception e) {
            log.warn("[资源下载] 下载失败: url={}, error={}", url, e.getMessage());
            return "Error downloading resource: " + e.getMessage();
        }
    }
}
