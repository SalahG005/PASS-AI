package com.pass.ai_assistant_backend.ai.dto;

import java.util.ArrayList;
import java.util.List;

public class ChatRequestDto {
    private String message;
    private List<ChatHistoryItemDto> history = new ArrayList<>();
    private String activePath;
    private List<String> mentionPaths = new ArrayList<>();
    private boolean useRag = true;
    /** Selected text in the active editor (Ctrl+K style context). */
    private String selection;
    /** Open editor tab paths for extra awareness. */
    private List<String> openPaths = new ArrayList<>();
    /** Optional RAG filters: lang:java, kind:class, class:Foo (also parsed from message). */
    private String ragLanguage;
    private String ragSymbolKind;
    private String ragSymbolContains;
    /** Request post-scaffold HTML layout check (optional; may add time on CPU). */
    private boolean scaffoldPreview = true;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<ChatHistoryItemDto> getHistory() {
        return history;
    }

    public void setHistory(List<ChatHistoryItemDto> history) {
        this.history = history != null ? history : new ArrayList<>();
    }

    public String getActivePath() {
        return activePath;
    }

    public void setActivePath(String activePath) {
        this.activePath = activePath;
    }

    public List<String> getMentionPaths() {
        return mentionPaths;
    }

    public void setMentionPaths(List<String> mentionPaths) {
        this.mentionPaths = mentionPaths != null ? mentionPaths : new ArrayList<>();
    }

    public boolean isUseRag() {
        return useRag;
    }

    public void setUseRag(boolean useRag) {
        this.useRag = useRag;
    }

    public String getSelection() {
        return selection;
    }

    public void setSelection(String selection) {
        this.selection = selection;
    }

    public List<String> getOpenPaths() {
        return openPaths;
    }

    public void setOpenPaths(List<String> openPaths) {
        this.openPaths = openPaths != null ? openPaths : new ArrayList<>();
    }

    public String getRagLanguage() {
        return ragLanguage;
    }

    public void setRagLanguage(String ragLanguage) {
        this.ragLanguage = ragLanguage;
    }

    public String getRagSymbolKind() {
        return ragSymbolKind;
    }

    public void setRagSymbolKind(String ragSymbolKind) {
        this.ragSymbolKind = ragSymbolKind;
    }

    public String getRagSymbolContains() {
        return ragSymbolContains;
    }

    public void setRagSymbolContains(String ragSymbolContains) {
        this.ragSymbolContains = ragSymbolContains;
    }

    public boolean isScaffoldPreview() {
        return scaffoldPreview;
    }

    public void setScaffoldPreview(boolean scaffoldPreview) {
        this.scaffoldPreview = scaffoldPreview;
    }
}
