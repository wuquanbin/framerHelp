package com.wqb.springboot.service.impl;

import com.wqb.springboot.dto.AiChatDto;
import com.wqb.springboot.dto.WeatherResponseDto;
import com.wqb.springboot.dto.WeatherResponseDto.CropReminder;
import com.wqb.springboot.dto.WeatherResponseDto.DailyFarmPlan;
import com.wqb.springboot.service.AgriWeatherService;
import com.wqb.springboot.service.AiChatService;
import com.wqb.springboot.service.CropGrowthEngine;
import com.wqb.springboot.service.LlmClientService;
import com.wqb.springboot.service.PromptTemplateService;
import com.wqb.springboot.service.RagFlowRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI智能对话服务实现类
 * <p>
 * 负责与AI大模型进行交互，为用户提供基于乡村农业气象数据的智能问答服务。
 * 结合天气上下文注入与农业知识库检索，实现农业场景化问答。
 * </p>
 */
@Service
public class AiChatServiceImpl implements AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatServiceImpl.class);

    /**
     * 兜底系统提示词
     * <p>
     * 只在 {@code prompt_template} 表里没有生效版本时才用到。
     * 日常调整提示词请去后台改模板（编码 {@code agri.chat.system}），不用改代码、不用重启。
     * 留着它是为了兜底：表被清空、Redis 挂了，AI 对话仍然能正常工作。
     * </p>
     */
    private static final String FALLBACK_SYSTEM_PROMPT = """
            你是佛山乡村农业气象帮扶AI助手，服务范围覆盖佛山市三水区、高明区、南海区、顺德区的帮扶村镇。你的职责是：

            1. 根据提供的实时天气数据、预报数据和预警信息，回答用户关于天气的问题
            2. 基于当前和未来天气状况，为农户提供农事建议（如是否适合施肥打药、灌溉时机、作物防寒防暑措施等）
            3. 解读气象预警信息的含义和对农业生产的影响，给出防灾减灾建议
            4. 根据当前天气推荐适合的农事活动，提示作物生长阶段的注意事项
            5. 回答关于农业气象、作物种植和乡村防灾的常识性问题

            回答要求：
            - 使用 Markdown 格式输出，合理使用标题(##)、列表(-)、加粗(**)、表格等
            - 结构清晰，层次分明，重要信息用加粗标记
            - 回答要简洁实用，一般不超过300字
            - 基于提供的天气数据回答，不要编造数据
            - 如果问题与农业气象帮扶无关，礼貌引导回农业气象话题
            - 使用友好亲切的语气
            - 若提供了【农业知识库参考】，涉及具体农事做法时优先依据它，并按要求标注来源
            """;

    /**
     * 兜底的「知识库使用规则」
     * <p>
     * 只在命中知识库片段时追加。同样可在后台改（编码 {@code agri.chat.knowledge.rules}）。
     * 这段是防幻觉的关键约束：要求标注来源、不许编造药剂剂量，
     * 对照实验里「有 RAG 组」和「无 RAG 组」的差异有一部分就来自这里。
     * </p>
     */
    private static final String FALLBACK_KNOWLEDGE_RULES = """
            - 上述片段来自本地农业技术资料，涉及具体农事做法时优先依据它们作答
            - 采用了某条片段的说法时，在该句末尾标注来源，格式为（来源：文件名）
            - 片段未覆盖的内容，如实说明知识库未收录并建议咨询当地农技站，不要编造具体药剂、剂量和操作时间
            """;

    private final AgriWeatherService weatherService;
    private final CropGrowthEngine cropGrowthEngine;
    private final RagFlowRetriever ragFlowRetriever;
    private final PromptTemplateService promptTemplateService;
    private final LlmClientService llmClient;

    public AiChatServiceImpl(AgriWeatherService weatherService,
                             CropGrowthEngine cropGrowthEngine,
                             RagFlowRetriever ragFlowRetriever,
                             PromptTemplateService promptTemplateService,
                             LlmClientService llmClient) {
        this.weatherService = weatherService;
        this.cropGrowthEngine = cropGrowthEngine;
        this.ragFlowRetriever = ragFlowRetriever;
        this.promptTemplateService = promptTemplateService;
        this.llmClient = llmClient;
    }


    @Override
    public AiChatDto.ChatResponse chat(AiChatDto.ChatRequest request) {
        ChatOutcome outcome = runChat(request, AiChatDto.EvalMode.FULL);
        return new AiChatDto.ChatResponse(outcome.reply, outcome.villageCode, outcome.villageName);
    }

    @Override
    public void streamChat(AiChatDto.ChatRequest request, java.util.function.Consumer<String> onDelta) {
        // 与普通对话共用同一份上下文，保证「流式」只是传输方式变了，回答内容不变
        BuiltMessages built = buildMessages(request, AiChatDto.EvalMode.FULL);
        llmClient.stream(built.messages(), onDelta);
    }

    @Override
    public AiChatDto.EvalResponse evalChat(AiChatDto.ChatRequest request, AiChatDto.EvalMode mode) {
        ChatOutcome outcome = runChat(request, mode == null ? AiChatDto.EvalMode.FULL : mode);
        boolean cited = outcome.reply != null && outcome.reply.contains("（来源：");
        return new AiChatDto.EvalResponse(
                mode, outcome.reply, outcome.elapsedMs, outcome.knowledgeUsed, cited);
    }

    /** 一次对话的中间结果，普通对话和评测共用 */
    private record ChatOutcome(
            String reply, String villageCode, String villageName,
            boolean knowledgeUsed, long elapsedMs) {}

    /**
     * 按评测模式逐级组装上下文并调用模型
     * <p>
     * 三样东西一层层往上加，正好对应论文里的四组对照实验：
     * {@code BARE} 什么都不加 → {@code PROMPT} 加提示词 → {@code CONTEXT} 再加天气数据
     * → {@code FULL} 再加知识库召回。正常对话固定走 {@code FULL}，行为与拆分前完全一致。
     * </p>
     */
    private ChatOutcome runChat(AiChatDto.ChatRequest request, AiChatDto.EvalMode mode) {
        BuiltMessages built = buildMessages(request, mode);
        String villageCode = built.villageCode();

        // 调用大模型生成回复（服务地址与模型可在后台「系统设置」改）
        long startedAt = System.currentTimeMillis();
        String reply = llmClient.complete(built.messages());
        long elapsedMs = System.currentTimeMillis() - startedAt;

        // 获取村落名称
        String villageName = "白坭镇";
        try {
            WeatherResponseDto.CurrentWeather current = weatherService.getCurrentWeather(villageCode);
            if (current != null) {
                villageName = current.villageName();
            }
        } catch (Exception ignored) {
            // 拿不到村名不影响对话（只用作品质地名的默认值），但要留痕：
            // 之前这里是空 catch，天气服务一旦挂了，日志里什么都看不到。
            log.warn("AI 对话取村落名称失败，villageCode={}，回退默认村镇名", villageCode);
        }

        return new ChatOutcome(reply, villageCode, villageName, built.knowledgeUsed(), elapsedMs);
    }

    /** 组装好的一次请求：村落编码（已补默认值）+ 消息列表 + 是否命中知识库 */
    private record BuiltMessages(
            String villageCode, List<Map<String, Object>> messages, boolean knowledgeUsed) {}

    /**
     * 组装喂给模型的完整消息列表（普通对话 / 流式 / 评测共用这一份）
     * <p>
     * 三样东西一层层往上加，正好对应论文里的四组对照实验：
     * {@code BARE} 什么都不加 → {@code PROMPT} 加提示词 → {@code CONTEXT} 再加天气数据
     * → {@code FULL} 再加知识库召回。正常对话固定走 {@code FULL}，行为与拆分前完全一致。
     * </p>
     */
    private BuiltMessages buildMessages(AiChatDto.ChatRequest request, AiChatDto.EvalMode mode) {
        String villageCode = request.villageCode();
        if (villageCode == null || villageCode.isBlank()) {
            villageCode = "baini";
        }

        boolean knowledgeUsed = false;
        List<Map<String, Object>> messages = new ArrayList<>();

        if (mode != AiChatDto.EvalMode.BARE) {
            StringBuilder systemPrompt = new StringBuilder();

            // 第一层：系统提示词
            systemPrompt.append(promptTemplateService.resolve(
                    PromptTemplateService.CODE_CHAT_SYSTEM, FALLBACK_SYSTEM_PROMPT));

            // 第二层：天气数据上下文（数据库侧：这个村此刻的真实状态）
            if (mode == AiChatDto.EvalMode.CONTEXT || mode == AiChatDto.EvalMode.FULL) {
                systemPrompt.append("\n\n当前天气数据上下文：\n").append(buildWeatherContext(villageCode));
            }

            // 第三层：知识库上下文（RAGFlow 侧：本地农技做法）
            if (mode == AiChatDto.EvalMode.FULL) {
                String knowledgeContext = buildKnowledgeContext(request.message());
                if (knowledgeContext != null) {
                    String rules = promptTemplateService.resolve(
                            PromptTemplateService.CODE_KNOWLEDGE_RULES, FALLBACK_KNOWLEDGE_RULES);
                    systemPrompt.append("\n\n【农业知识库参考】\n").append(knowledgeContext)
                            .append("\n\n知识库使用规则：\n").append(rules).append("\n");
                    knowledgeUsed = true;
                }
            }

            messages.add(Map.of("role", "system", "content", systemPrompt.toString()));

            // 添加历史对话（裸模式下不带，保持"裸"）
            if (request.history() != null) {
                for (AiChatDto.ChatMessage msg : request.history()) {
                    messages.add(Map.of("role", msg.role(), "content", msg.content()));
                }
            }
        }

        // 添加当前用户消息
        messages.add(Map.of("role", "user", "content", request.message()));

        return new BuiltMessages(villageCode, messages, knowledgeUsed);
    }

    /**
     * 构建农业知识库上下文（RAG 的检索侧）
     * <p>
     * 拿用户原始问题去 RAGFlow 换回若干条本地农技知识片段。这是与「天气上下文」互补的第二路：
     * 天气数据来自数据库，回答的是「这个村此刻是什么情况」；
     * 知识库来自农技资料，回答的是「这种情况该怎么做」。
     * </p>
     *
     * <p>这里再包一层 try-catch 是刻意的：{@link RagFlowRetriever} 内部已经吞过一次异常，
     * 但检索是前置步骤，绝不允许因为知识库把整个对话接口拖到失败。
     * 拿不到就返回 null，调用方按「没有知识库」继续走。</p>
     *
     * @param query 用户问题
     * @return 格式化后的知识片段；未启用、无命中或调用失败时为 null
     */
    private String buildKnowledgeContext(String query) {
        try {
            String ctx = ragFlowRetriever.formatSnippets(ragFlowRetriever.retrieve(query));
            if (ctx != null) {
                log.info("知识库上下文已注入，{} 字符，开头：{}",
                        ctx.length(), ctx.substring(0, Math.min(120, ctx.length())).replace("\n", " / "));
            } else {
                log.info("知识库无命中或未启用，本次对话仅使用天气上下文");
            }
            return ctx;
        } catch (Exception e) {
            log.warn("构建知识库上下文失败，本次对话不使用知识库: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建天气上下文信息
     * <p>
     * 将实时天气、预报数据、预警信息、农事建议、作物档案和灾害记录
     * 汇总为文本上下文，供AI模型参考使用。
     * </p>
     *
     * @param villageCode 村落编码
     * @return 格式化的天气上下文字符串
     */
    @SuppressWarnings("unchecked")
    private String buildWeatherContext(String villageCode) {
        StringBuilder sb = new StringBuilder();

        try {
            // 实时天气
            WeatherResponseDto.CurrentWeather current = weatherService.getCurrentWeather(villageCode);
            if (current != null) {
                sb.append("【实时天气-").append(current.villageName()).append("】\n");
                sb.append("天气：").append(current.weatherType()).append("\n");
                sb.append("温度：").append(current.temperature()).append("°C\n");
                sb.append("体感温度：").append(current.apparentTemperature()).append("°C\n");
                sb.append("湿度：").append(current.humidity()).append("%\n");
                sb.append("风向风力：").append(current.windDirection()).append(current.windScale()).append("\n");
                sb.append("降水概率：").append(current.precipitationProbability()).append("%\n");
                sb.append("空气质量：").append(current.airQuality()).append("\n");
                sb.append("农事指数：").append(current.farmIndex()).append("\n\n");
            }

            // 天气预报
            List<WeatherResponseDto.ForecastDay> forecast = weatherService.getForecast(villageCode);
            if (forecast != null && !forecast.isEmpty()) {
                sb.append("【未来天气预报】\n");
                for (WeatherResponseDto.ForecastDay day : forecast) {
                    sb.append(day.forecastDate())
                            .append(" ").append(day.weekLabel())
                            .append(" ").append(day.weatherType())
                            .append(" ").append(day.lowTemperature()).append("°~").append(day.highTemperature()).append("°")
                            .append(" 降水").append(day.precipitationProbability()).append("%")
                            .append(" ").append(day.windDirection()).append(day.windScale());
                    if (day.farmingAdvice() != null && !day.farmingAdvice().isBlank()) {
                        sb.append(" 农事建议：").append(day.farmingAdvice());
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            // 活跃预警
            List<WeatherResponseDto.WarningNoticeItem> warnings = weatherService.listActiveWarnings();
            if (warnings != null && !warnings.isEmpty()) {
                sb.append("【当前气象预警】\n");
                for (WeatherResponseDto.WarningNoticeItem w : warnings) {
                    sb.append("- ").append(w.title())
                            .append("（").append(w.severity()).append("级）")
                            .append(" 影响区域：").append(w.impactArea());
                    if (w.defenseGuidance() != null) {
                        sb.append(" 防御指引：").append(w.defenseGuidance());
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            // 农事建议
            List<WeatherResponseDto.FarmAdviceItem> advices = weatherService.getFarmAdvices(villageCode);
            if (advices != null && !advices.isEmpty()) {
                sb.append("【农事建议】\n");
                for (WeatherResponseDto.FarmAdviceItem s : advices) {
                    sb.append("- ").append(s.title()).append("：").append(s.recommendation()).append("\n");
                }
                sb.append("\n");
            }

            // 作物档案
            List<WeatherResponseDto.CropProfileItem> crops = weatherService.getCropProfiles(villageCode);
            if (crops != null && !crops.isEmpty()) {
                sb.append("【作物档案】\n");
                for (WeatherResponseDto.CropProfileItem c : crops) {
                    sb.append("- ").append(c.cropName())
                            .append("（").append(c.cropType()).append("）")
                            .append(" 种植面积：").append(c.plantingArea())
                            .append(" 生长阶段：").append(c.growthStage())
                            .append(" 主要作物：").append(c.isMainCrop() ? "是" : "否")
                            .append(" 风险等级：").append(c.riskLevel()).append("/5")
                            .append("\n");
                }
            }

            // 灾害记录
            List<WeatherResponseDto.DisasterRecordItem> disasters = weatherService.getDisasterRecords(villageCode);
            if (disasters != null && !disasters.isEmpty()) {
                sb.append("\n【灾害记录】\n");
                for (WeatherResponseDto.DisasterRecordItem r : disasters) {
                    sb.append("- ").append(r.disasterName())
                            .append(" 灾害类型：").append(r.disasterType())
                            .append(" 补救措施：").append(r.remedy())
                            .append("\n");
                }
            }

            // 作物农事提醒
            try {
                List<CropReminder> reminders = cropGrowthEngine.generateCropReminders(villageCode);
                if (reminders != null && !reminders.isEmpty()) {
                    sb.append("\n【作物农事提醒】\n");
                    for (CropReminder r : reminders) {
                        sb.append("- ").append(r.cropName())
                                .append("(").append(r.growthStage()).append("): ")
                                .append(r.suggestion())
                                .append(" [紧急度:").append(r.urgency()).append("]\n");
                    }
                }
            } catch (Exception e) {
                log.warn("获取作物农事提醒失败: {}", e.getMessage());
            }

            // 本周农事计划
            try {
                List<DailyFarmPlan> plans = cropGrowthEngine.getWeeklyFarmPlan(villageCode);
                if (plans != null && !plans.isEmpty()) {
                    sb.append("\n【本周农事计划】\n");
                    for (DailyFarmPlan plan : plans) {
                        sb.append(plan.date())
                                .append("(").append(plan.weekLabel()).append("): ")
                                .append("天气").append(plan.weatherType())
                                .append(", 建议").append(String.join(", ", plan.activities()))
                                .append(". ").append(plan.caution())
                                .append("\n");
                    }
                }
            } catch (Exception e) {
                log.warn("获取本周农事计划失败: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.warn("构建天气上下文失败: {}", e.getMessage());
            sb.append("暂无天气数据\n");
        }

        return sb.toString();
    }
}
