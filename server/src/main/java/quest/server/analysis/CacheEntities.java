package quest.server.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The permanent AI cache (§4): analyses and generated plays / stops / panels keyed by source hash + prompt version. */
public final class CacheEntities {
    private CacheEntities() {}

    @Entity @Table(name = "analysis_cache")
    public static class AnalysisCacheEntity {
        @Id @Column(name = "cache_key") private String cacheKey;
        @Column(name = "source_hash", nullable = false) private String sourceHash;
        @Column(nullable = false) private String curriculum; @Column(nullable = false) private int grade; @Column(nullable = false) private String subject;
        @Column(name = "prompt_version", nullable = false) private String promptVersion;
        @Column(name = "analysis_json", nullable = false) private String analysisJson;
        @Column(name = "token_usage", nullable = false) private long tokenUsage; @Column(nullable = false) private int hits;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getCacheKey() { return cacheKey; } public void setCacheKey(String v) { cacheKey = v; }
        public String getSourceHash() { return sourceHash; } public void setSourceHash(String v) { sourceHash = v; }
        public String getCurriculum() { return curriculum; } public void setCurriculum(String v) { curriculum = v; }
        public int getGrade() { return grade; } public void setGrade(int v) { grade = v; }
        public String getSubject() { return subject; } public void setSubject(String v) { subject = v; }
        public String getPromptVersion() { return promptVersion; } public void setPromptVersion(String v) { promptVersion = v; }
        public String getAnalysisJson() { return analysisJson; } public void setAnalysisJson(String v) { analysisJson = v; }
        public long getTokenUsage() { return tokenUsage; } public void setTokenUsage(long v) { tokenUsage = v; }
        public int getHits() { return hits; } public void setHits(int v) { hits = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    @Entity @Table(name = "generation_cache")
    public static class GenerationCacheEntity {
        @Id @Column(name = "cache_key") private String cacheKey;
        @Column(name = "source_hash", nullable = false) private String sourceHash;
        @Column(nullable = false) private String kind;
        @Column(name = "prompt_version", nullable = false) private String promptVersion;
        @Column(nullable = false) private String json;
        @Column(name = "token_usage", nullable = false) private long tokenUsage; @Column(nullable = false) private int hits;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getCacheKey() { return cacheKey; } public void setCacheKey(String v) { cacheKey = v; }
        public String getSourceHash() { return sourceHash; } public void setSourceHash(String v) { sourceHash = v; }
        public String getKind() { return kind; } public void setKind(String v) { kind = v; }
        public String getPromptVersion() { return promptVersion; } public void setPromptVersion(String v) { promptVersion = v; }
        public String getJson() { return json; } public void setJson(String v) { json = v; }
        public long getTokenUsage() { return tokenUsage; } public void setTokenUsage(long v) { tokenUsage = v; }
        public int getHits() { return hits; } public void setHits(int v) { hits = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
