package com.wqb.springboot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wqb.springboot.service.AppConfigService;
import com.wqb.springboot.service.LlmClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 大模型调用客户端实现
 * <p>
 * 统一走 OpenAI 兼容的 {@code POST /chat/completions}。
 * 取值顺序：<b>后台「系统设置」表 → application.properties → 注解里的默认值</b>。
 * 也就是说换服务商只需要改配置，不用动代码。
 * </p>
 *
 * <p>两种调用方式：
 * <ul>
 *   <li>{@link #complete(List)} 一次性拿完整文本（后台评测、农情受理走这条）</li>
 *   <li>{@link #stream(List, java.util.function.Consumer)} 流式：拿到一小段就回调一次，
 *       给小程序做逐字输出，避免用户盯着空白等好几秒</li>
 * </ul>
 * 两者共用同一套「地址 / 模型 / 密钥 / 温度」取值规则，行为不会出现分叉。
 * </p>
 */
@Service
public class LlmClientServiceImpl implements LlmClientService {

    private static final Logger log = LoggerFactory.getLogger(LlmClientServiceImpl.class);

    /** 流式读取的最长等待：模型慢或网络卡时，不能让连接无限挂着 */
    private static final int STREAM_TIMEOUT_SECONDS = 180;

    /** 调用失败时给用户看的兜底文案（不抛异常、不返回 null，前端永远有东西可显示） */
    private static final String FALLBACK_REPLY = "抱歉，AI助手暂时无法回答，请稍后再试。";

    /** 默认服务地址（云端） */
    @Value("${ai.base-url:https://api.deepseek.com/v1}")
    private String defaultBaseUrl;
    /** 默认模型名 */
    @Value("${ai.model:deepseek-chat}")
    private String defaultModel;
    /** 默认密钥，云端服务必填 */
    @Value("${ai.api-key:}")
    private String defaultApiKey;
    @Value("${ai.temperature:0.7}")
    private double defaultTemperature;
    @Value("${ai.max-tokens:1024}")
    private int defaultMaxTokens;

    private final AppConfigService appConfigService;
    private final RestTemplate restTemplate;
    /**
     * 流式分片是 JSON 解析，自己持有一个最朴素的 mapper，
     * 不复用容器里那个（Redis 用的那份开了 DefaultTyping，语义不一样）。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmClientServiceImpl(AppConfigService appConfigService,
                                @Qualifier("aiRestTemplate") RestTemplate restTemplate) {
        this.appConfigService = appConfigService;
        this.restTemplate = restTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String complete(List<Map<String, Object>> messages) {
        try {
            String url = resolveUrl();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String apiKey = resolveApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                headers.setBearerAuth(apiKey.trim());
            }

            Map<String, Object> body = buildBody(messages, false);

            log.info("调用大模型接口: url={}, model={}", url, body.get("model"));
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null && message.get("content") != null) {
                        return String.valueOf(message.get("content"));
                    }
                }
            }
            log.warn("大模型接口返回异常状态: {}", response.getStatusCode());
            return FALLBACK_REPLY;

        } catch (Exception e) {
            log.error("大模型接口调用失败: {}", e.getMessage());
            return FALLBACK_REPLY;
        }
    }

    @Override
    public void stream(List<Map<String, Object>> messages, java.util.function.Consumer<String> onDelta) {
        AtomicBoolean sentAny = new AtomicBoolean(false);
        try {
            String url = resolveUrl();
            String jsonBody = objectMapper.writeValueAsString(buildBody(messages, true));

            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(STREAM_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            // 缺密钥的警告在 resolveApiKey() 里统一打，这里不重复
            String apiKey = resolveApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey.trim());
            }

            HttpResponse<InputStream> response = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                // 上游拒绝：直接抛出去，交给下面的兜底处理，不要假装成功
                throw new IllegalStateException("上游返回 " + response.statusCode());
            }

            // SSE：一行 data: 一个分片，读到 [DONE] 结束
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    String delta = extractDelta(payload);
                    if (delta != null && !delta.isEmpty()) {
                        sentAny.set(true);
                        onDelta.accept(delta);
                    }
                }
            }
        } catch (Exception e) {
            log.error("大模型流式调用失败: {}", e.getMessage());
            // 关键：只有「一个字都还没发出去」时才回退成一次性调用。
            // 已经吐了半截再补整段，前端会看到内容重复一遍。
            if (!sentAny.get()) {
                onDelta.accept(complete(messages));
            } else {
                throw new IllegalStateException("流式生成中断", e);
            }
        }
    }

    /** 从 SSE 分片里取出增量文本；首包只有 role 没有 content 时返回 null，跳过即可 */
    private String extractDelta(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return null;
            }
            JsonNode delta = choices.get(0).get("delta");
            if (delta == null || delta.get("content") == null || delta.get("content").isNull()) {
                return null;
            }
            return delta.get("content").asText();
        } catch (Exception e) {
            log.warn("流式分片解析失败（跳过该片）: {}", e.getMessage());
            return null;
        }
    }

    /** 请求体：两种调用共用，只有 stream 开关不同 */
    private Map<String, Object> buildBody(List<Map<String, Object>> messages, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolveModel());
        body.put("messages", messages);
        body.put("stream", stream);
        body.put("temperature", appConfigService.getDouble(
                AppConfigService.K_AI_TEMPERATURE, defaultTemperature));
        body.put("max_tokens", appConfigService.getInt(
                AppConfigService.K_AI_MAX_TOKENS, defaultMaxTokens));
        return body;
    }

    private String resolveUrl() {
        String base = appConfigService.get(AppConfigService.K_AI_BASE_URL, defaultBaseUrl);
        if (base == null || base.isBlank()) {
            base = defaultBaseUrl;
        }
        return base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions";
    }

    /** 云端服务一律要求鉴权；没配密钥时留一条明确的警告，免得只看到 401 却不知道原因 */
    private String resolveApiKey() {
        String apiKey = appConfigService.get(AppConfigService.K_AI_API_KEY, defaultApiKey);
        if ((apiKey == null || apiKey.isBlank()) && (defaultApiKey == null || defaultApiKey.isBlank())) {
            log.warn("未配置 ai.api.key，本次调用不带鉴权头，云端服务大概率返回 401");
        }
        return apiKey;
    }

    /** 模型名：后台「系统设置」的 ai.model 优先，没配才用默认值 */
    private String resolveModel() {
        return appConfigService.get(AppConfigService.K_AI_MODEL, defaultModel);
    }
}
