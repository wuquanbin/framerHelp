package com.wqb.springboot.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wqb.springboot.dto.FarmReportAiDto;
import com.wqb.springboot.service.FarmReportAiService;
import com.wqb.springboot.service.LlmClientService;
import com.wqb.springboot.service.PromptTemplateService;
import com.wqb.springboot.service.RagFlowRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 农情上报智能受理实现
 * <p>
 * 流程：农户原话 → 知识库召回相关农技片段 → 模型按 JSON 结构输出工单草稿 → 解析回对象。
 * 与 AI 对话共用 {@link LlmClientService} 和 {@link RagFlowRetriever}，
 * 不重复实现一套模型调用。
 * </p>
 *
 * <p><b>降级优先</b>：模型挂了、返回不是 JSON、字段缺失，任何一种情况都不会让上报失败，
 * 而是退回「原话直填」的草稿（{@code aiParsed=false}）——农户照样能把问题报上来，
 * 只是少了自动整理。这条底线很重要：受理是业务流程的第一环，不能被 AI 卡住。</p>
 */
@Service
public class FarmReportAiServiceImpl implements FarmReportAiService {

    private static final Logger log = LoggerFactory.getLogger(FarmReportAiServiceImpl.class);

    /** 提示词兜底：表里没配时用这份 */
    private static final String FALLBACK_PROMPT = """
            你是乡村农业气象帮扶系统的农情受理助手，负责把农户的口语化描述整理成规范工单。

            只输出一个 JSON 对象，不要输出任何解释文字、不要加 Markdown 代码块标记，字段如下：
            {"title":"不超过20字的标题","cropName":"作物名，识别不出就填空字符串",
             "reportType":"pest|disaster|growth|help 之一","urgency":"low|medium|high 之一",
             "description":"整理后的客观描述，保留农户提到的关键事实（时间、部位、范围、程度），不要添加未提及的信息",
             "suggestedAction":"初步处置建议，参考提供的农技资料；资料没覆盖就写建议联系当地农技站"}

            类型判定：pest=病虫害，disaster=气象或自然灾害，growth=生长异常/栽培咨询，help=其他求助。
            紧急度：high=正在扩散或已造成明显损失、需48小时内处理；medium=需要关注但可安排在数日内；low=常规咨询。
            不要编造农户没说的事实；识别不出的字段留空或给保守值。
            """;

    private final LlmClientService llmClient;
    private final PromptTemplateService promptTemplateService;
    private final RagFlowRetriever ragFlowRetriever;

    /**
     * 自己 new 一个，不注入容器的那个：这里只解析模型吐出的简单 JSON，
     * 用不上 MVC 那套序列化配置，也就没必要依赖容器里有没有这个 bean
     * （Spring Boot 4 默认不把它暴露成可注入 bean，注入会直接启动失败）。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FarmReportAiServiceImpl(LlmClientService llmClient,
                                   PromptTemplateService promptTemplateService,
                                   RagFlowRetriever ragFlowRetriever) {
        this.llmClient = llmClient;
        this.promptTemplateService = promptTemplateService;
        this.ragFlowRetriever = ragFlowRetriever;
    }

    @Override
    public FarmReportAiDto.ParseResult parse(FarmReportAiDto.ParseRequest request) {
        String raw = request == null ? null : request.rawText();
        if (raw == null || raw.isBlank()) {
            return fallback("", "");
        }

        String knowledge = null;
        try {
            knowledge = ragFlowRetriever.formatSnippets(ragFlowRetriever.retrieve(raw));
        } catch (Exception e) {
            log.warn("受理时知识库检索失败，继续解析（只是少了参考资料）: {}", e.getMessage());
        }

        String system = promptTemplateService.resolve(CODE_FARM_REPORT_PARSE, FALLBACK_PROMPT);
        StringBuilder user = new StringBuilder("农户原话：").append(raw);
        if (request.villageCode() != null && !request.villageCode().isBlank()) {
            user.append("\n所在村镇编码：").append(request.villageCode());
        }
        if (knowledge != null) {
            user.append("\n\n可参考的本地农技资料：\n").append(knowledge);
        }

        String text = llmClient.complete(List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user.toString())));

        Map<String, String> fields = tryParseJson(text);
        if (fields == null) {
            log.warn("受理解析失败，降级为原话直填。模型输出前 120 字：{}",
                    text == null ? "" : text.substring(0, Math.min(120, text.length())));
            return fallback(raw, null);
        }

        return new FarmReportAiDto.ParseResult(
                blankToDefault(fields.get("title"), raw.length() > 20 ? raw.substring(0, 20) : raw),
                blankToDefault(fields.get("cropName"), ""),
                normalizeType(fields.get("reportType")),
                normalizeUrgency(fields.get("urgency")),
                blankToDefault(fields.get("description"), raw),
                blankToDefault(fields.get("suggestedAction"), "建议联系当地农技站现场诊断"),
                true);
    }

    /**
     * 从模型输出里抠出 JSON 字段
     * <p>
     * 模型经常把 JSON 包在 ```json 代码块里，或者前后带一句废话。
     * 这里做两件事：剥掉代码块标记，再从第一个 { 截到最后一个 }，最后按 Map 读。
     * 用 Map 而不是绑定到一个 record 上，是因为字段可能缺、值可能是数字，
     * 绑定反而容易抛异常；缺字段在后面用保守默认值兜住。
     * </p>
     */
    private Map<String, String> tryParseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            int firstBrace = cleaned.indexOf('{');
            int lastFence = cleaned.lastIndexOf("```");
            cleaned = firstBrace >= 0 && lastFence > firstBrace
                    ? cleaned.substring(firstBrace, lastFence)
                    : cleaned.replace("```json", "").replace("```", "");
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            Map<?, ?> raw = objectMapper.readValue(cleaned.substring(start, end + 1), Map.class);
            Map<String, String> result = new java.util.HashMap<>();
            raw.forEach((k, v) -> result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
            return result;
        } catch (Exception e) {
            log.warn("受理结果 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 兜底草稿：把原话原样当工单，保证业务不中断 */
    private FarmReportAiDto.ParseResult fallback(String raw, String action) {
        String title = raw == null || raw.isBlank() ? "农情上报" : raw.substring(0, Math.min(20, raw.length()));
        return new FarmReportAiDto.ParseResult(title, "", "help", "medium",
                raw == null ? "" : raw, action == null ? "建议联系当地农技站现场诊断" : action, false);
    }

    private static String blankToDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }

    /** 类型只认这四个值，其余按求助处理 */
    private static String normalizeType(String v) {
        if (v == null) return "help";
        String t = v.trim().toLowerCase();
        return switch (t) {
            case "pest", "disaster", "growth" -> t;
            default -> "help";
        };
    }

    private static String normalizeUrgency(String v) {
        if (v == null) return "medium";
        String u = v.trim().toLowerCase();
        return switch (u) {
            case "high", "low" -> u;
            default -> "medium";
        };
    }
}
