package com.wqb.springboot.controller;

import com.wqb.springboot.dto.AiChatDto;
import com.wqb.springboot.dto.ApiResponse;
import com.wqb.springboot.service.AiChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 回答质量评测接口（论文对照实验用）
 * <p>
 * 存在的意义：要说清「提示词 / 天气上下文 / 知识库」每一层到底贡献了多少，
 * 就得让它们在同一个题库上各跑一遍。这个接口把模式开关暴露出来，
 * 评测脚本循环调它就能批量出数据，不用改代码、不用重启。
 * </p>
 *
 * <p>与普通对话 {@code /api/ai/chat} 的关系：走的是同一个 {@code AiChatService}，
 * 区别只在于按模式少喂几样东西；正常对话固定等价于 {@code FULL}，不受影响。</p>
 */
@RestController
@RequestMapping("/api/admin/ai-eval")
public class AiEvalController {

    private static final Logger log = LoggerFactory.getLogger(AiEvalController.class);

    private final AiChatService aiChatService;

    public AiEvalController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    /**
     * 按指定模式跑一次问答
     *
     * @param request 含问题、村镇编码、模式名（BARE / PROMPT / CONTEXT / FULL）
     */
    @PostMapping("/run")
    public ApiResponse<AiChatDto.EvalResponse> run(@RequestBody AiChatDto.EvalRunRequest request) {
        AiChatDto.EvalMode mode = parseMode(request.mode());
        AiChatDto.ChatRequest chatRequest = new AiChatDto.ChatRequest(
                request.message(),
                request.villageCode() == null || request.villageCode().isBlank() ? "baini" : request.villageCode(),
                null);
        try {
            AiChatDto.EvalResponse res = aiChatService.evalChat(chatRequest, mode);
            log.info("[评测] mode={}, 耗时={}ms, 命中知识库={}, 标注来源={}, 问题={}",
                    mode, res.elapsedMs(), res.knowledgeUsed(), res.cited(), request.message());
            return ApiResponse.ok(res);
        } catch (Exception e) {
            return ApiResponse.fail("评测调用失败: " + e.getMessage(), null);
        }
    }

    /** 模式名容错：大小写、空格都不挑，认不出来就按完整版跑 */
    private AiChatDto.EvalMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return AiChatDto.EvalMode.FULL;
        }
        try {
            return AiChatDto.EvalMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知的评测模式「{}」，按 FULL 处理", raw);
            return AiChatDto.EvalMode.FULL;
        }
    }
}
