package com.pass.ai_assistant_backend.ai;

import jakarta.persistence.*;

@Entity
@Table(name = "embedding_chunks", indexes = {
        @Index(name = "idx_emb_ws", columnList = "email, workspaceId"),
        @Index(name = "idx_emb_path", columnList = "email, workspaceId, path"),
        @Index(name = "idx_emb_lang", columnList = "email, workspaceId, language"),
        @Index(name = "idx_emb_symbol", columnList = "email, workspaceId, symbolName")
})
public class EmbeddingChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(nullable = false, length = 120)
    private String workspaceId;

    @Column(nullable = false, length = 1024)
    private String path;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** JSON float array — portable without requiring pgvector extension. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String embeddingJson;

    @Column(length = 40)
    private String language;

    /** class | method | function | interface | enum | record | section | module | block | file */
    @Column(length = 40)
    private String symbolKind;

    @Column(length = 255)
    private String symbolName;

    @Column(length = 255)
    private String parentSymbol;

    private Integer startLine;

    private Integer endLine;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getEmbeddingJson() {
        return embeddingJson;
    }

    public void setEmbeddingJson(String embeddingJson) {
        this.embeddingJson = embeddingJson;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getSymbolKind() {
        return symbolKind;
    }

    public void setSymbolKind(String symbolKind) {
        this.symbolKind = symbolKind;
    }

    public String getSymbolName() {
        return symbolName;
    }

    public void setSymbolName(String symbolName) {
        this.symbolName = symbolName;
    }

    public String getParentSymbol() {
        return parentSymbol;
    }

    public void setParentSymbol(String parentSymbol) {
        this.parentSymbol = parentSymbol;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public void setStartLine(Integer startLine) {
        this.startLine = startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }

    public void setEndLine(Integer endLine) {
        this.endLine = endLine;
    }
}
