package com.wqb.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAGFlow 知识库检索配置
 * <p>
 * 从配置文件读取 {@code ragflow.*} 前缀的属性。运行时可在后台「系统设置」里
 * 通过 {@code rag.enabled} / {@code rag.top.k} / {@code rag.score.threshold} 覆盖，
 * 具体见 {@link com.wqb.springboot.service.AppConfigService} 的同名键。
 * </p>
 *
 * <p><b>默认是关的</b>：{@code enabled=false}。RAGFlow 跑在 Docker 里，本机 16GB 内存，
 * 不可能保证它任何时候都在。关掉时系统行为与接入前完全一致，不会有任何功能退化。</p>
 */
@Component
@ConfigurationProperties(prefix = "ragflow")
public class RagFlowProperties {

    /** 是否启用知识库检索（关闭时 AiChatService 行为与接入前一致） */
    private boolean enabled = false;
    /** RAGFlow HTTP API 根地址（docker-compose 默认 9380 端口） */
    private String baseUrl = "http://localhost:9380";
    /** RAGFlow 后台「API Key」页面生成的密钥 */
    private String apiKey = "";
    /** 知识库（dataset）ID，浏览器进知识库时 URL 里那串 */
    private String datasetId = "";
    /** 召回条数 */
    private int topK = 4;
    /**
     * 相似度阈值
     * <p>
     * 实测本知识库（bge-m3 + bge-reranker-v2-m3）命中区间大致在 0.27—0.45，
     * 定 0.15 是为了宁可多召回一条让模型自己筛，也不要把有用的片段滤掉。
     */
    private double scoreThreshold = 0.15;
    /** 单条片段最大字符数，超出截断，防止少数超长片段吃满上下文 */
    private int maxSnippetChars = 500;
    /** 注入 prompt 的知识片段总字符上限 */
    private int maxTotalChars = 1800;
    /** 连续失败多少次后临时熔断 */
    private int circuitBreakerThreshold = 3;
    /** 熔断后隔多久重新试探（毫秒） */
    private long circuitBreakerCooldownMs = 60_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getScoreThreshold() {
        return scoreThreshold;
    }

    public void setScoreThreshold(double scoreThreshold) {
        this.scoreThreshold = scoreThreshold;
    }

    public int getMaxSnippetChars() {
        return maxSnippetChars;
    }

    public void setMaxSnippetChars(int maxSnippetChars) {
        this.maxSnippetChars = maxSnippetChars;
    }

    public int getMaxTotalChars() {
        return maxTotalChars;
    }

    public void setMaxTotalChars(int maxTotalChars) {
        this.maxTotalChars = maxTotalChars;
    }

    public int getCircuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }

    public void setCircuitBreakerThreshold(int circuitBreakerThreshold) {
        this.circuitBreakerThreshold = circuitBreakerThreshold;
    }

    public long getCircuitBreakerCooldownMs() {
        return circuitBreakerCooldownMs;
    }

    public void setCircuitBreakerCooldownMs(long circuitBreakerCooldownMs) {
        this.circuitBreakerCooldownMs = circuitBreakerCooldownMs;
    }
}
