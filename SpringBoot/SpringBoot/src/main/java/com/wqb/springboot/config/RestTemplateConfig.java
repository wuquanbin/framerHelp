package com.wqb.springboot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate HTTP客户端配置类
 * <p>
 * 配置两个RestTemplate实例：一个用于普通HTTP请求（短超时），
 * 另一个专用于AI接口调用（长超时，适应大模型流式响应的耗时特性）。
 * </p>
 */
@Configuration
public class RestTemplateConfig {

    /**
     * 通用RestTemplate实例，用于普通HTTP请求
     * <p>
     * 连接超时5秒，读取超时10秒，适用于天气数据、地图API等常规接口。
     * </p>
     *
     * @return 配置好超时参数的RestTemplate实例
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);  // 连接超时：5秒
        factory.setReadTimeout(10000);    // 读取超时：10秒
        return new RestTemplate(factory);
    }

    /**
     * AI专用RestTemplate实例，用于调用大模型/AI接口
     * <p>
     * 连接超时10秒，读取超时120秒（2分钟），适应AI大模型响应较慢的特点。
     * </p>
     *
     * @return 配置好长超时参数的RestTemplate实例
     */
    @Bean(name = "aiRestTemplate")
    public RestTemplate aiRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);   // 连接超时：10秒
        factory.setReadTimeout(120000);     // 读取超时：120秒（AI响应耗时较长）
        return new RestTemplate(factory);
    }

    /**
     * RAGFlow 检索专用 RestTemplate（短超时）
     * <p>
     * 存在的意义：检索是 AI 对话的<b>前置步骤</b>，它的耗时直接叠加到用户等待时间上。
     * 复用 aiRestTemplate（120 秒）的话，RAGFlow 一旦无响应，整个对话接口会被拖死两分钟。
     * 这里单独给一个 1.5 + 2.5 秒的短超时，配合 {@code RagFlowRetriever} 的熔断，
     * 保证「知识库不可用」时最多只多花 2.5 秒，然后照常回答。
     * </p>
     */
    @Bean(name = "ragflowRestTemplate")
    public RestTemplate ragflowRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1500);    // 连接超时：1.5秒
        factory.setReadTimeout(2500);       // 读取超时：2.5秒
        return new RestTemplate(factory);
    }
}
