package com.pass.ai_assistant_backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ollama")
public class OllamaProperties {

    private String baseUrl = "http://localhost:11434";
    /** Chat / explain model. Prefer pass-ai-coder after `ollama create`. */
    private String chatModel = "pass-ai-coder";
    /** Fallback if chatModel is missing. */
    private String chatModelFallback = "qwen2.5-coder:7b";
    /** Agent / tool loop model. Prefer pass-ai-agent after `ollama create`. */
    private String agentModel = "pass-ai-agent";
    private String agentModelFallback = "qwen2.5-coder:7b";
    private String embedModel = "nomic-embed-text";
    private int timeoutSeconds = 180;
    private int ragTopK = 5;
    private int ragMaxChars = 12000;
    private int chunkSize = 800;
    private int chunkOverlap = 100;
    private int indexMaxFiles = 200;
    private int indexMaxFileBytes = 200_000;
    private int maxToolSteps = 6;
    private int maxScaffoldToolSteps = 24;
    private boolean agentVerify = true;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public String getChatModelFallback() {
        return chatModelFallback;
    }

    public void setChatModelFallback(String chatModelFallback) {
        this.chatModelFallback = chatModelFallback;
    }

    public String getAgentModel() {
        return agentModel;
    }

    public void setAgentModel(String agentModel) {
        this.agentModel = agentModel;
    }

    public String getAgentModelFallback() {
        return agentModelFallback;
    }

    public void setAgentModelFallback(String agentModelFallback) {
        this.agentModelFallback = agentModelFallback;
    }

    public String getEmbedModel() {
        return embedModel;
    }

    public void setEmbedModel(String embedModel) {
        this.embedModel = embedModel;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getRagTopK() {
        return ragTopK;
    }

    public void setRagTopK(int ragTopK) {
        this.ragTopK = ragTopK;
    }

    public int getRagMaxChars() {
        return ragMaxChars;
    }

    public void setRagMaxChars(int ragMaxChars) {
        this.ragMaxChars = ragMaxChars;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public int getIndexMaxFiles() {
        return indexMaxFiles;
    }

    public void setIndexMaxFiles(int indexMaxFiles) {
        this.indexMaxFiles = indexMaxFiles;
    }

    public int getIndexMaxFileBytes() {
        return indexMaxFileBytes;
    }

    public void setIndexMaxFileBytes(int indexMaxFileBytes) {
        this.indexMaxFileBytes = indexMaxFileBytes;
    }

    public int getMaxToolSteps() {
        return maxToolSteps;
    }

    public void setMaxToolSteps(int maxToolSteps) {
        this.maxToolSteps = maxToolSteps;
    }

    public int getMaxScaffoldToolSteps() {
        return maxScaffoldToolSteps;
    }

    public void setMaxScaffoldToolSteps(int maxScaffoldToolSteps) {
        this.maxScaffoldToolSteps = maxScaffoldToolSteps;
    }

    public boolean isAgentVerify() {
        return agentVerify;
    }

    public void setAgentVerify(boolean agentVerify) {
        this.agentVerify = agentVerify;
    }

    /** Prefer custom Modelfile; fall back to base coder for 6GB GPUs. */
    public String resolveChatModel() {
        return chatModel != null && !chatModel.isBlank() ? chatModel : chatModelFallback;
    }

    public String resolveAgentModel() {
        return agentModel != null && !agentModel.isBlank() ? agentModel : agentModelFallback;
    }
}
