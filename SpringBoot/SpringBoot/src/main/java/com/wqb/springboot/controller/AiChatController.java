package com.wqb.springboot.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wqb.springboot.dto.AiChatDto;
import com.wqb.springboot.dto.ApiResponse;
import com.wqb.springboot.service.AiChatService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI对话控制器
 * <p>
 * 提供AI智能对话接口，接收用户消息并返回AI回复。
 * 两个端点：
 * <ul>
 *   <li>{@code POST /api/ai/chat} 一次性返回整段回复（后台、评测、兜底用）</li>
 *   <li>{@code POST /api/ai/chat/stream} SSE 流式，生成一点推一点（小程序用它做逐字输出）</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);

    /** 流式连接最长挂 3 分钟，超时由 SseEmitter 自己收尾，不让连接泄漏 */
    private static final long STREAM_TIMEOUT_MS = 180_000L;

    private final AiChatService aiChatService;

    /** 流式推送要一直占住一个线程读上游，不能占用 Tomcat 请求线程，否则并发几个对话就把 Web 容器堵死 */
    private final ExecutorService streamExecutor;

    /**
     * 每个分片单独 JSON 编码后再发：SSE 转换器会把字符串里的换行按行拆成多个 {@code data:} 行，
     * 客户端按行读就会丢掉换行、Markdown 段落列表全糊；编码后换行变成 {@code \n}，一行一分片。
     * 用独立的朴素 mapper，不碰容器里那份（Redis 用的开了 DefaultTyping）。
     */
    private final ObjectMapper jsonCodec = new ObjectMapper();

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
        AtomicLong seq = new AtomicLong();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "ai-stream-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.streamExecutor = Executors.newCachedThreadPool(factory);
    }

    /**
     * AI对话接口
     * <p>
     * 接收用户的聊天请求，调用AI服务生成回复。如果调用失败则返回错误信息。
     * </p>
     *
     * @param request 聊天请求体，包含用户消息内容
     * @return AI聊天响应，包含回复内容
     */
    @PostMapping("/chat")
    public ApiResponse<AiChatDto.ChatResponse> chat(@RequestBody AiChatDto.ChatRequest request) {
        try {
            AiChatDto.ChatResponse response = aiChatService.chat(request);
            return ApiResponse.ok(response);
        } catch (Exception e) {
            // AI服务调用异常时返回失败响应
            return ApiResponse.fail("AI对话失败: " + e.getMessage(), null);
        }
    }

    /**
     * AI 流式对话接口（SSE）
     * <p>
     * 与普通对话拼的是同一份上下文，区别只在传输方式：模型每产出一段就推一个
     * {@code data:} 事件，前端边收边渲染，用户不用盯着空白等整段憋完。
     * </p>
     *
     * <p>三个容易被忽略的细节：
     * <ol>
     *   <li>{@code X-Accel-Buffering: no} —— nginx / 隧道默认会攒够一块才往下发，
     *       不关掉的话流式会退化成「最后一次性到达」；</li>
     *   <li>用 JSON 编码分片 —— 回复里的换行在 SSE 里会被当成新行拆断，
     *       JSON 转义后前端 {@code JSON.parse} 回来才是原文；</li>
     *   <li>断开即停 —— 客户端跑路时 {@code send} 抛 IOException，
     *       这里转成异常结束整个生成，不再白读上游。</li>
     * </ol>
     * </p>
     *
     * @param request  聊天请求体（与普通对话完全一样）
     * @param response 原始响应，用来手动关掉中间层缓冲
     * @return SSE 发射器，事件体是 JSON 字符串（一段增量文本）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody AiChatDto.ChatRequest request,
                                 HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        // 超时不让 Spring 抛 500，安静结束即可：客户端最多是少了结尾几个字
        emitter.onTimeout(emitter::complete);

        streamExecutor.execute(() -> {
            try {
                aiChatService.streamChat(request, delta -> {
                    try {
                        emitter.send(SseEmitter.event().data(jsonCodec.writeValueAsString(delta),
                                MediaType.APPLICATION_JSON));
                    } catch (JsonProcessingException e) {
                        // 编码失败就退化成原样发送：丢一个分片的格式，也比整句丢掉强
                        sendRaw(emitter, delta);
                    } catch (IOException e) {
                        throw new IllegalStateException("客户端已断开连接", e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                log.warn("流式对话中断: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** 直接发原文（编码失败时的退路） */
    private void sendRaw(SseEmitter emitter, String delta) {
        try {
            emitter.send(SseEmitter.event().data(delta));
        } catch (IOException e) {
            throw new IllegalStateException("客户端已断开连接", e);
        }
    }

    @PreDestroy
    public void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
    }
}
