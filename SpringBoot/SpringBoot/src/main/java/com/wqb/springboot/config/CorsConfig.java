package com.wqb.springboot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS跨域配置类
 * <p>
 * 基于Spring MVC的WebMvcConfigurer实现跨域配置，
 * 允许本地开发环境（localhost和127.0.0.1的所有端口）跨域访问后端API，
 * 适用于非Security拦截的普通跨域场景。
 * </p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.upload.path:D:/weather-uploads}")
    private String uploadPath;

    /**
     * 配置CORS跨域映射规则
     * <p>
     * 仅对/api路径生效，允许本地开发地址的跨域请求，
     * 支持GET/POST/PUT/DELETE/OPTIONS方法，预检缓存1小时。
     * </p>
     *
     * @param registry CORS注册器
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 对所有路径启用跨域（包括/api和/uploads）
                // 开发环境允许所有来源，包括局域网真机调试；上线后可改为具体域名
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 允许的HTTP方法
                .allowedHeaders("*") // 允许所有请求头
                .allowCredentials(true)
                .maxAge(3600); // 预检请求缓存时间：1小时
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPath + "/");
    }
}
