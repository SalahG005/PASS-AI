package com.pass.ai_assistant_backend.ai;

import com.pass.ai_assistant_backend.ai.dto.ChatRequestDto;
import com.pass.ai_assistant_backend.ai.dto.IndexResultDto;
import com.pass.ai_assistant_backend.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class EmbeddingService {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "dist", "build", ".idea", ".vscode",
            "__pycache__", ".pass-ai", "out", ".gradle", "vendor"
    );

    private static final Set<String> TEXT_EXT = Set.of(
            "java", "ts", "tsx", "js", "jsx", "json", "md", "txt", "xml", "yml", "yaml",
            "properties", "css", "scss", "html", "py", "go", "rs", "c", "h", "cpp", "hpp",
            "cs", "kt", "sql", "sh", "ps1", "bat", "gradle", "toml", "ini", "env"
    );

    private final EmbeddingChunkRepository repository;
    private final OllamaClient ollama;
    private final OllamaProperties props;
    private final WorkspaceService workspaceService;
    private final ObjectMapper mapper;

    public EmbeddingService(
            EmbeddingChunkRepository repository,
            OllamaClient ollama,
            OllamaProperties props,
            WorkspaceService workspaceService,
            ObjectMapper mapper
    ) {
        this.repository = repository;
        this.ollama = ollama;
        this.props = props;
        this.workspaceService = workspaceService;
        this.mapper = mapper;
    }

    @Transactional
    public IndexResultDto indexWorkspace(String email, String workspaceId) throws IOException, InterruptedException {
        String ws = workspaceId == null || workspaceId.isBlank() ? "default" : workspaceId;
        Path root = workspaceService.resolveUserRoot(email, workspaceId);
        List<Path> files = collectFiles(root);
        repository.deleteByEmailAndWorkspaceId(email, ws);

        int filesIndexed = 0;
        int chunksStored = 0;
        List<EmbeddingChunk> batch = new ArrayList<>();

        for (Path file : files) {
            if (filesIndexed >= props.getIndexMaxFiles()) {
                break;
            }
            long size = Files.size(file);
            if (size <= 0 || size > props.getIndexMaxFileBytes()) {
                continue;
            }
            String relative = root.relativize(file).toString().replace('\\', '/');
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank() || looksBinary(content)) {
                continue;
            }
            List<CodeAwareChunker.CodeChunk> chunks =
                    CodeAwareChunker.chunk(relative, content, props.getChunkSize(), props.getChunkOverlap());
            int idx = 0;
            for (CodeAwareChunker.CodeChunk chunk : chunks) {
                String embedText = buildEmbedText(relative, chunk);
                float[] vec = ollama.embed(embedText);
                EmbeddingChunk entity = new EmbeddingChunk();
                entity.setEmail(email);
                entity.setWorkspaceId(ws);
                entity.setPath(relative);
                entity.setChunkIndex(idx++);
                entity.setContent(chunk.content());
                entity.setEmbeddingJson(toJson(vec));
                entity.setLanguage(chunk.language());
                entity.setSymbolKind(chunk.symbolKind());
                entity.setSymbolName(chunk.symbolName());
                entity.setParentSymbol(chunk.parentSymbol());
                entity.setStartLine(chunk.startLine());
                entity.setEndLine(chunk.endLine());
                batch.add(entity);
                chunksStored++;
            }
            filesIndexed++;
            if (batch.size() >= 32) {
                repository.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            repository.saveAll(batch);
        }

        return new IndexResultDto(
                true,
                filesIndexed,
                chunksStored,
                "Indexed " + filesIndexed + " files into " + chunksStored + " structure-aware chunks"
        );
    }

    public RagContext retrieve(String email, String workspaceId, String query) throws IOException, InterruptedException {
        return retrieve(email, workspaceId, query, null);
    }

    public RagContext retrieve(String email, String workspaceId, String query, ChatRequestDto request)
            throws IOException, InterruptedException {
        String ws = workspaceId == null || workspaceId.isBlank() ? "default" : workspaceId;
        List<EmbeddingChunk> all = repository.findByEmailAndWorkspaceId(email, ws);
        if (all.isEmpty() || query == null || query.isBlank()) {
            return RagContext.empty();
        }

        RagFilters filters = RagFilters.from(query, request);
        String embedQuery = filters.cleanedQuery().isBlank() ? query : filters.cleanedQuery();
        float[] q = ollama.embed(embedQuery);

        List<ScoredChunk> scored = new ArrayList<>();
        for (EmbeddingChunk chunk : all) {
            if (!filters.matches(chunk)) {
                continue;
            }
            float[] v = fromJson(chunk.getEmbeddingJson());
            double score = cosine(q, v) + filters.boost(chunk);
            scored.add(new ScoredChunk(chunk, score));
        }
        if (scored.isEmpty()) {
            for (EmbeddingChunk chunk : all) {
                float[] v = fromJson(chunk.getEmbeddingJson());
                scored.add(new ScoredChunk(chunk, cosine(q, v)));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());

        StringBuilder sb = new StringBuilder();
        Set<String> paths = new HashSet<>();
        int used = 0;
        for (ScoredChunk s : scored) {
            if (used >= props.getRagTopK()) {
                break;
            }
            if (sb.length() + s.chunk().getContent().length() > props.getRagMaxChars()) {
                break;
            }
            paths.add(s.chunk().getPath());
            sb.append("--- ").append(formatChunkHeader(s.chunk())).append(" ---\n")
                    .append(s.chunk().getContent()).append("\n\n");
            used++;
        }
        return new RagContext(sb.toString().trim(), new ArrayList<>(paths));
    }

    private static String buildEmbedText(String path, CodeAwareChunker.CodeChunk chunk) {
        StringBuilder sb = new StringBuilder();
        sb.append("path=").append(path);
        if (chunk.language() != null) {
            sb.append(" lang=").append(chunk.language());
        }
        if (chunk.symbolKind() != null) {
            sb.append(" kind=").append(chunk.symbolKind());
        }
        if (chunk.parentSymbol() != null) {
            sb.append(" parent=").append(chunk.parentSymbol());
        }
        if (chunk.symbolName() != null) {
            sb.append(" symbol=").append(chunk.symbolName());
        }
        if (chunk.startLine() > 0) {
            sb.append(" lines=").append(chunk.startLine()).append('-').append(chunk.endLine());
        }
        sb.append('\n').append(chunk.content());
        return sb.toString();
    }

    private static String formatChunkHeader(EmbeddingChunk c) {
        StringBuilder sb = new StringBuilder(c.getPath() == null ? "?" : c.getPath());
        if (c.getStartLine() != null) {
            sb.append(':').append(c.getStartLine());
            if (c.getEndLine() != null && !c.getEndLine().equals(c.getStartLine())) {
                sb.append('-').append(c.getEndLine());
            }
        }
        if (c.getLanguage() != null && !c.getLanguage().isBlank()) {
            sb.append(" [").append(c.getLanguage()).append(']');
        }
        if (c.getSymbolKind() != null && c.getSymbolName() != null) {
            sb.append(' ').append(c.getSymbolKind()).append(' ').append(c.getSymbolName());
            if (c.getParentSymbol() != null && !c.getParentSymbol().isBlank()) {
                sb.append(" in ").append(c.getParentSymbol());
            }
        } else if (c.getSymbolName() != null) {
            sb.append(' ').append(c.getSymbolName());
        }
        return sb.toString();
    }

    public long chunkCount(String email, String workspaceId) {
        String ws = workspaceId == null || workspaceId.isBlank() ? "default" : workspaceId;
        return repository.countByEmailAndWorkspaceId(email, ws);
    }

    private List<Path> collectFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isSkipped(root, p))
                    .filter(this::isTextLike)
                    .forEach(out::add);
        }
        out.sort(Comparator.comparing(p -> p.toString().toLowerCase(Locale.ROOT)));
        return out;
    }

    private boolean isSkipped(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path part : rel) {
            if (SKIP_DIRS.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isTextLike(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return name.equalsIgnoreCase("Dockerfile") || name.equalsIgnoreCase("Makefile");
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TEXT_EXT.contains(ext);
    }

    private static boolean looksBinary(String content) {
        int n = Math.min(content.length(), 2000);
        for (int i = 0; i < n; i++) {
            char c = content.charAt(i);
            if (c == 0) {
                return true;
            }
        }
        return false;
    }

    static List<String> chunkText(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int step = Math.max(1, size - Math.max(0, overlap));
        for (int i = 0; i < text.length(); i += step) {
            int end = Math.min(text.length(), i + size);
            chunks.add(text.substring(i, end));
            if (end >= text.length()) {
                break;
            }
        }
        return chunks;
    }

    private String toJson(float[] vec) {
        return mapper.writeValueAsString(vec);
    }

    private float[] fromJson(String json) {
        return mapper.readValue(json, float[].class);
    }

    static double cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public record RagContext(String promptBlock, List<String> paths) {
        static RagContext empty() {
            return new RagContext("", List.of());
        }
    }

    private record ScoredChunk(EmbeddingChunk chunk, double score) {
    }

    /**
     * Query filters: {@code lang:java}, {@code kind:class}, {@code class:Foo} / {@code symbol:Foo},
     * plus request fields and soft boosts for names ending in Service.
     */
    static final class RagFilters {
        private static final Pattern TOKEN = Pattern.compile(
                "(?i)\\b(?:lang|language|kind|class|symbol|in):([\\w.$]+)"
        );

        private final String cleanedQuery;
        private final String language;
        private final String symbolKind;
        private final String symbolContains;

        private RagFilters(String cleanedQuery, String language, String symbolKind, String symbolContains) {
            this.cleanedQuery = cleanedQuery;
            this.language = language;
            this.symbolKind = symbolKind;
            this.symbolContains = symbolContains;
        }

        static RagFilters from(String query, ChatRequestDto request) {
            String language = request != null ? blankToNull(request.getRagLanguage()) : null;
            String kind = request != null ? blankToNull(request.getRagSymbolKind()) : null;
            String symbol = request != null ? blankToNull(request.getRagSymbolContains()) : null;

            String cleaned = query == null ? "" : query;
            Matcher m = TOKEN.matcher(cleaned);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String key = m.group(0).split(":", 2)[0].toLowerCase(Locale.ROOT);
                String val = m.group(1);
                if (key.startsWith("lang")) {
                    language = val.toLowerCase(Locale.ROOT);
                } else if (key.equals("kind")) {
                    kind = val.toLowerCase(Locale.ROOT);
                } else if (key.equals("class") || key.equals("symbol") || key.equals("in")) {
                    symbol = val;
                    if (key.equals("class") && kind == null) {
                        kind = "class";
                    }
                }
                m.appendReplacement(sb, " ");
            }
            m.appendTail(sb);
            cleaned = sb.toString().replaceAll("\\s+", " ").trim();

            // Soft intent: "Service classes" without explicit filter
            if (symbol == null && query != null) {
                String q = query.toLowerCase(Locale.ROOT);
                if (q.contains("service class") || q.contains("classes service") || q.matches(".*\\b\\w*service\\b.*classes.*")) {
                    symbol = "Service";
                    if (kind == null) {
                        kind = "class";
                    }
                }
            }
            return new RagFilters(cleaned, language, kind, symbol);
        }

        String cleanedQuery() {
            return cleanedQuery;
        }

        boolean matches(EmbeddingChunk c) {
            if (language != null) {
                String lang = c.getLanguage() == null ? "" : c.getLanguage().toLowerCase(Locale.ROOT);
                if (!lang.equals(language) && !lang.startsWith(language)) {
                    return false;
                }
            }
            if (symbolKind != null) {
                String k = c.getSymbolKind() == null ? "" : c.getSymbolKind().toLowerCase(Locale.ROOT);
                if (!k.equals(symbolKind) && !(symbolKind.equals("class") && Set.of("class", "interface", "enum", "record").contains(k))) {
                    return false;
                }
            }
            if (symbolContains != null) {
                String needle = symbolContains.toLowerCase(Locale.ROOT);
                String name = c.getSymbolName() == null ? "" : c.getSymbolName().toLowerCase(Locale.ROOT);
                String parent = c.getParentSymbol() == null ? "" : c.getParentSymbol().toLowerCase(Locale.ROOT);
                String path = c.getPath() == null ? "" : c.getPath().toLowerCase(Locale.ROOT);
                if (!(name.contains(needle) || parent.contains(needle) || path.contains(needle))) {
                    return false;
                }
            }
            return true;
        }

        double boost(EmbeddingChunk c) {
            double b = 0;
            if (symbolContains != null && c.getSymbolName() != null
                    && c.getSymbolName().toLowerCase(Locale.ROOT).contains(symbolContains.toLowerCase(Locale.ROOT))) {
                b += 0.08;
            }
            if (c.getSymbolName() != null && c.getSymbolName().endsWith("Service")
                    && "class".equalsIgnoreCase(c.getSymbolKind())) {
                b += 0.02;
            }
            return b;
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }
}
