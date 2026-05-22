package com.yupi.yuaiagent.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 图片搜索工具 —— 通过 Pexels API 搜索免费图片
 */
public class ImageSearchTool {

    private static final String API_URL = "https://api.pexels.com/v1/search";

    private final String apiKey;

    public ImageSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(description = """
            Search free stock images from Pexels API.
            Use this tool when: user asks to find, see, get, or look at images/photos/pictures/illustrations.
            Do NOT use for: text search, facts, news, or general questions (use searchWeb instead).
            query: translate to English descriptive keywords (e.g. "sunset mountain landscape").
            Returns image URLs in Markdown format that render directly as visible images in chat.
            This is the ONLY tool that can get images — searchWeb cannot find images.
            """)
    public String searchImage(@ToolParam(description = "Search keywords for images") String query) {
        try {
            List<String> urls = searchMediumImages(query);
            if (urls.isEmpty()) {
                return "未找到相关图片: " + query;
            }
            return urls.stream()
                    .map(url -> "![" + query + "](" + url + ")")
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "Error search image: " + e.getMessage();
        }
    }

    private List<String> searchMediumImages(String query) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", apiKey);

        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("per_page", 5);

        String response = HttpUtil.createGet(API_URL)
                .addHeaders(headers)
                .form(params)
                .execute()
                .body();

        return JSONUtil.parseObj(response)
                .getJSONArray("photos")
                .stream()
                .map(photoObj -> (JSONObject) photoObj)
                .map(photoObj -> photoObj.getJSONObject("src"))
                .map(photo -> photo.getStr("medium"))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }
}
