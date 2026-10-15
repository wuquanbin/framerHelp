package com.wqb.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 高德地图服务配置类
 * <p>
 * 从配置文件中读取以"amap"为前缀的属性，
 * 用于配置高德地图REST API的密钥和基础请求地址。
 * </p>
 */
@Configuration
@ConfigurationProperties(prefix = "amap")
public class AmapConfig {

    /** 高德地图API密钥，用于接口鉴权 */
    private String apiKey = "98fe5dbee13fd24b9f219e2350ffcc77";
    /** 高德地图REST API基础地址 */
    private String baseUrl = "https://restapi.amap.com";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
