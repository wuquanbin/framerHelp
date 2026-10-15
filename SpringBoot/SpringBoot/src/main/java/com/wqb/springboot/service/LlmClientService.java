package com.wqb.springboot.service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 大模型调用客户端（OpenAI 兼容格式）
 * <p>
 * 抽出来是为了让「AI 对话」和「农情智能受理」共用同一套调用逻辑：
 * 模型名 / 温度 / 密钥 / 地址的取值规则、超时、错误兜底写一份就够，
 * 将来换服务商也只改这一处。
 * </p>
 *
 * <p>只负责「把消息发给模型、把返回的文本带回来」，
 * 不掺任何业务语义——提示词怎么拼、上下文怎么注入由调用方决定。</p>
 */
public interface LlmClientService {

    /**
     * 发送一轮对话并返回模型输出的文本
     *
     * @param messages 消息列表，元素形如 {@code {role=system|user|assistant, content=文本}}
     * @return 模型输出；调用失败时返回一句友好的兜底文案（不会抛异常、不会返回 null）
     */
    String complete(List<Map<String, Object>> messages);

    /**
     * 流式发送一轮对话：模型每产出一小段文本就回调一次
     * <p>
     * 用于小程序「逐字输出」，避免用户盯着空白等模型憋完一整段。
     * 回调里不要做耗时操作——它跑在流式读取线程上，卡住会把后面的分片一起堵住。
     * </p>
     *
     * <p>失败语义：一个字都还没发出去时，自动降级为 {@link #complete(List)} 并整段回调一次；
     * 已经吐过内容再断开则抛异常，由调用方决定怎么收尾（前端保留已收到的部分）。</p>
     *
     * @param messages 消息列表，元素形如 {@code {role=system|user|assistant, content=文本}}
     * @param onDelta  增量文本回调，可能被调用很多次
     */
    void stream(List<Map<String, Object>> messages, Consumer<String> onDelta);
}
