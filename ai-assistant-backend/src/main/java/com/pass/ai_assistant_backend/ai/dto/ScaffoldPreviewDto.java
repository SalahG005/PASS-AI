package com.pass.ai_assistant_backend.ai.dto;

import java.util.ArrayList;
import java.util.List;

/** Optional post-scaffold HTML layout check + screenshot. */
public class ScaffoldPreviewDto {
    private String htmlPath;
    /** PNG bytes as base64 (may be empty if headless browser unavailable). */
    private String screenshotBase64 = "";
    private List<String> warnings = new ArrayList<>();
    private boolean screenshotAvailable;

    public String getHtmlPath() {
        return htmlPath;
    }

    public void setHtmlPath(String htmlPath) {
        this.htmlPath = htmlPath;
    }

    public String getScreenshotBase64() {
        return screenshotBase64;
    }

    public void setScreenshotBase64(String screenshotBase64) {
        this.screenshotBase64 = screenshotBase64 == null ? "" : screenshotBase64;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings != null ? warnings : new ArrayList<>();
    }

    public boolean isScreenshotAvailable() {
        return screenshotAvailable;
    }

    public void setScreenshotAvailable(boolean screenshotAvailable) {
        this.screenshotAvailable = screenshotAvailable;
    }
}
