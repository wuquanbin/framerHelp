package com.wqb.springboot.service;

import com.wqb.springboot.dto.AiChatDto;

import java.util.function.Consumer;

/**
 * AI智能对话服务接口
 * <p>
 * 负责与AI大模型进行交互，为用户提供基于乡村农业气象数据的智能问答服务。
 * </p>
 */
public interface AiChatService {

    /**
     * 处理用户聊天请求
     * <p>
     * 根据用户所在村落获取天气上下文，结合历史对话和当前消息，调用大模型生成回复。
     * </p>
     *
     * @param request 聊天请求，包含用户消息、村落编码和历史对话
     * @return 聊天响应，包含AI回复内容、村落编码和村落名称
     */
    AiChatDto.ChatResponse chat(AiChatDto.ChatRequest request);

    /**
     * 按评测模式跑一次对话（供论文对照实验使用）
     * <p>
     * 与 {@link #chat(AiChatDto.ChatRequest)} 走同一条链路，区别只是按
     * {@link AiChatDto.EvalMode} 决定要不要加系统提示词、天气上下文、知识库片段。
     * 这样四组对照跑出来才有可比性——变的只有"喂了多少上下文"，模型、参数、
     * 上下文拼装逻辑全都一样。
     * </p>
     *
     * @param request 聊天请求
     * @param mode    评测模式
     */
    AiChatDto.EvalResponse evalChat(AiChatDto.ChatRequest request, AiChatDto.EvalMode mode);

    /**
     * 流式对话：回复不是一次给全，而是生成一点推一点
     * <p>
     * 与 {@link #chat(AiChatDto.ChatRequest)} 拼的是同一份上下文（提示词 + 天气 + 知识库），
     * 只是把「等模型憋完再整段返回」换成「边生成边回调」，给小程序做逐字输出。
     * 上下文组装逻辑只有 {@code buildMessages} 一份，两条路不会出现回答口径不一致。
     * </p>
     *
     * @param request  聊天请求
     * @param onDelta  增量文本回调，会被调用多次；每次给的是新产出的那一小段
     */
    void streamChat(AiChatDto.ChatRequest request, Consumer<String> onDelta);
}
