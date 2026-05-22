package com.yupi.yuaiagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 网页抓取工具
 */
public class WebScrapingTool {

    @Tool(description = """
            Scrape the full HTML content of a web page to read detailed information.
            Use this tool when: searchWeb returned a link, and the snippet is not enough — you need to read the full article.
            Do NOT use for: general search (use searchWeb), images (use searchImage), made-up URLs.
            url: must be a real URL from searchWeb results, starting with http:// or https://
            Returns the full HTML of the page (may be very long, automatically truncated for analysis).
            """)
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        try {
            Document document = Jsoup.connect(url).get();
            return document.html();
        } catch (Exception e) {
            return "Error scraping web page: " + e.getMessage();
        }
    }
}
