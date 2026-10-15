package com.wqb.springboot.service;

import com.wqb.springboot.config.RagFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RAGFlow 知识库检索器（RAG 中的 Retrieval 一侧）
 * <p>
 * 只做一件事：拿用户的问题去 RAGFlow 换回若干条农技知识片段。
 * <b>生成（Generation）仍然由 {@code AiChatServiceImpl} 负责</b> ——
 * 模型、提示词、天气上下文注入都还在自己手里，知识库只是多喂一路参考材料。
 * 这样分工的好处是：RAGFlow 换掉、挂掉、甚至整个删掉，系统照常能答，
 * 只是少了本地农技知识这一路参考，而不是整个 AI 模块瘫痪。
 * </p>
 *
 * <p>容错策略（答辩机不一定开着 Docker，必须保证不拖垮主流程）：
 * <ol>
 *   <li>关闭时（{@code ragflow.enabled=false}）直接返回空，零开销</li>
 *   <li>网络异常、超时、返回结构异常一律吞掉并返回空，只打日志</li>
 *   <li>连续失败达阈值后熔断一段时间，避免每次对话都白等 2.5 秒</li>
 * </ol>
 * </p>
 */
@Service
public class RagFlowRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagFlowRetriever.class);

    /** 一条召回片段：正文、来源文件名、相似度 */
    public record Snippet(String content, String title, double score) {}

    private final RagFlowProperties props;
    private final AppConfigService appConfigService;
    private final RestTemplate restTemplate;

    /** 连续失败次数 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** 熔断解除时间戳（毫秒），0 表示未熔断 */
    private volatile long openUntil = 0L;

    public RagFlowRetriever(RagFlowProperties props,
                            AppConfigService appConfigService,
                            @Qualifier("ragflowRestTemplate") RestTemplate restTemplate) {
        this.props = props;
        this.appConfigService = appConfigService;
        this.restTemplate = restTemplate;
    }

    /**
     * 检索农技知识片段
     *
     * @param query 用户问题
     * @return 命中的片段列表；未启用、无结果、调用失败时返回空列表（不会为 null）
     */
    @SuppressWarnings("unchecked")
    public List<Snippet> retrieve(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (!isEnabled()) {
            return List.of();
        }
        if (isCircuitOpen()) {
            log.debug("RAGFlow 处于熔断期，跳过检索");
            return List.of();
        }

        String datasetId = appConfigService.get(AppConfigService.K_RAG_DATASET_ID, props.getDatasetId());
        String apiKey = appConfigService.get(AppConfigService.K_RAG_API_KEY, props.getApiKey());
        if (datasetId == null || datasetId.isBlank() || apiKey == null || apiKey.isBlank()) {
            log.warn("RAGFlow 未配置 datasetId 或 apiKey，跳过检索");
            return List.of();
        }

        try {
            int topK = appConfigService.getInt(AppConfigService.K_RAG_TOP_K, props.getTopK());
            double threshold = appConfigService.getDouble(
                    AppConfigService.K_RAG_SCORE_THRESHOLD, props.getScoreThreshold());

            String url = buildUrl();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> setting = new LinkedHashMap<>();
            setting.put("top_k", topK);
            setting.put("score_threshold", threshold);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("knowledge_id", datasetId);
            body.put("query", query);
            body.put("retrieval_setting", setting);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("RAGFlow 检索返回异常状态：{}", response.getStatusCode());
                recordFailure();
                return List.of();
            }

            List<Snippet> snippets = parse(response.getBody());
            recordSuccess();
            log.info("RAGFlow 检索命中 {} 条，query={}", snippets.size(), query);
            return snippets;

        } catch (Exception e) {
            // 任何异常都不向上抛：知识库是增强项，不是主流程
            log.warn("RAGFlow 检索失败（已降级为不使用知识库）：{}", e.getMessage());
            recordFailure();
            return List.of();
        }
    }

    /**
     * 把片段格式化成可注入 prompt 的文本
     *
     * @param snippets {@link #retrieve(String)} 的结果
     * @return 组装好的文本；片段为空时返回 null，调用方据此决定是否追加
     */
    public String formatSnippets(List<Snippet> snippets) {
        if (snippets == null || snippets.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int index = 1;
        for (Snippet s : snippets) {
            String content = s.content() == null ? "" : s.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > props.getMaxSnippetChars()) {
                content = content.substring(0, props.getMaxSnippetChars()) + "…";
            }
            if (used + content.length() > props.getMaxTotalChars()) {
                break;
            }
            sb.append("[").append(index++).append("] 来源：").append(s.title() == null ? "农业知识库" : s.title())
                    .append("（相似度 ").append(String.format("%.2f", s.score())).append("）\n")
                    .append(content).append("\n\n");
            used += content.length();
        }
        return sb.length() == 0 ? null : sb.toString().trim();
    }

    /** 是否启用（后台配置优先，未配置则取 yml） */
    private boolean isEnabled() {
        return appConfigService.getBool(AppConfigService.K_RAG_ENABLED, props.isEnabled());
    }

    private String buildUrl() {
        String base = appConfigService.get(AppConfigService.K_RAG_BASE_URL, props.getBaseUrl());
        if (base == null || base.isBlank()) {
            base = props.getBaseUrl();
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/api/v1/dify/retrieval";
    }

    @SuppressWarnings("unchecked")
    private List<Snippet> parse(Map<String, Object> responseBody) {
        List<Snippet> result = new ArrayList<>();
        Object records = responseBody.get("records");
        if (!(records instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String content = str(m.get("content"));
            String title = str(m.get("title"));
            double score = 0d;
            Object rawScore = m.get("score");
            if (rawScore instanceof Number n) {
                score = n.doubleValue();
            }
            result.add(new Snippet(content, title, score));
        }
        return result;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private boolean isCircuitOpen() {
        return System.currentTimeMillis() < openUntil;
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
        openUntil = 0L;
    }

    private void recordFailure() {
        int n = consecutiveFailures.incrementAndGet();
        if (n >= props.getCircuitBreakerThreshold()) {
            openUntil = System.currentTimeMillis() + props.getCircuitBreakerCooldownMs();
            consecutiveFailures.set(0);
            log.warn("RAGFlow 连续失败 {} 次，熔断 {} 秒",
                    n, props.getCircuitBreakerCooldownMs() / 1000);
        }
    }
}
