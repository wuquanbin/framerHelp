package com.wqb.springboot.config;

import com.wqb.springboot.security.AdminRole;
import com.wqb.springboot.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security安全配置类
 * <p>
 * 配置JWT无状态认证、CORS跨域策略、接口权限控制及密码编码器。
 * 公开接口（天气、预警、农事建议等）无需认证，
 * 管理端接口需要JWT Token认证后方可访问。
 * </p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * 配置安全过滤器链
     * <p>
     * 定义CORS跨域、CSRF禁用、无状态Session、接口权限规则，
     * 并在UsernamePasswordAuthenticationFilter之前插入JWT认证过滤器。
     * </p>
     *
     * @param http HttpSecurity构建器
     * @return 构建完成的SecurityFilterChain
     * @throws Exception 配置异常
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource())) // 启用CORS跨域
            .csrf(csrf -> csrf.disable()) // 禁用CSRF（前后端分离架构使用JWT，无需CSRF防护）
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 无状态Session，不使用HttpSession
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/login").permitAll()           // 管理员登录接口公开
                .requestMatchers("/api/wechat/login").permitAll()          // 微信登录接口公开
                .requestMatchers(HttpMethod.GET, "/api/wechat/feedbacks").authenticated() // 用户反馈需认证
                .requestMatchers(HttpMethod.GET, "/api/wechat/unread-responses-count").authenticated() // 未读回复数需登录
                .requestMatchers(HttpMethod.GET, "/api/wechat/**").permitAll() // 微信端GET请求公开
                .requestMatchers(HttpMethod.POST, "/api/wechat/feedback").authenticated() // 微信反馈需认证
                .requestMatchers(HttpMethod.POST, "/api/wechat/read-responses").authenticated() // 标记回复已读需登录
                .requestMatchers(HttpMethod.POST, "/api/wechat/avatar").authenticated()  // 头像上传需认证
                .requestMatchers(HttpMethod.PUT, "/api/wechat/**").authenticated()  // 微信端PUT需认证
                .requestMatchers(HttpMethod.DELETE, "/api/wechat/**").authenticated() // 微信端DELETE需认证
                .requestMatchers("/api/districts").permitAll()             // 区域信息公开
                .requestMatchers("/api/dashboard/**").permitAll()          // 数据概览公开
                .requestMatchers("/api/farm/**").permitAll()               // 农事建议公开
                .requestMatchers("/api/ai/**").permitAll()                 // AI对话接口公开
                .requestMatchers("/api/meta/**").permitAll()              // 元数据接口公开
                .requestMatchers("/api/weather/**").permitAll()            // 天气数据公开
                .requestMatchers("/api/warnings/**").permitAll()           // 预警信息公开
                .requestMatchers(HttpMethod.GET, "/api/farm-reports/**").permitAll()       // 农情上报查询公开
                .requestMatchers(HttpMethod.POST, "/api/farm-reports/upload-image").authenticated() // 图片上传需认证
                .requestMatchers(HttpMethod.POST, "/api/farm-reports").authenticated()     // 新建上报需认证
                .requestMatchers(HttpMethod.POST, "/api/farm-reports/**").authenticated()  // 新建响应等写操作需认证
                .requestMatchers(HttpMethod.PUT, "/api/farm-reports/**").authenticated()   // 修改上报需认证
                .requestMatchers(HttpMethod.DELETE, "/api/farm-reports/**").authenticated()// 删除上报需认证
                .requestMatchers("/ws/**").permitAll()                     // WebSocket实时人工客服（握手在HandshakeInterceptor中鉴权）
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()    // 预检请求全部放行

                // ========== 管理端：三级角色权限矩阵 ==========
                // 顺序敏感：越具体的规则必须越靠前，否则会被后面的 /api/admin/** authenticated 吃掉。
                // 人员/参数/审计仅超管；删除默认超管+专家；其余管理端接口任意已登录账号可访问。
                .requestMatchers("/api/admin/admins/**").hasAuthority(AdminRole.SUPER_ADMIN.authority())
                .requestMatchers("/api/admin/config/**").hasAuthority(AdminRole.SUPER_ADMIN.authority())
                // 提示词模板：编辑放行超管+专家，启用与删除只超管（改错会让整个 AI 模块跑偏且不报错）
                .requestMatchers(HttpMethod.POST, "/api/admin/prompt-templates").hasAnyAuthority(
                        AdminRole.SUPER_ADMIN.authority(), AdminRole.EXPERT.authority())
                .requestMatchers(HttpMethod.PUT, "/api/admin/prompt-templates/**").hasAnyAuthority(
                        AdminRole.SUPER_ADMIN.authority(), AdminRole.EXPERT.authority())
                .requestMatchers(HttpMethod.POST, "/api/admin/prompt-templates/*/activate").hasAuthority(
                        AdminRole.SUPER_ADMIN.authority())
                .requestMatchers(HttpMethod.DELETE, "/api/admin/prompt-templates/**").hasAuthority(
                        AdminRole.SUPER_ADMIN.authority())
                // 评测接口会真实调云端模型、产生费用，不对外公开；超管+专家可用
                .requestMatchers("/api/admin/ai-eval/**").hasAnyAuthority(
                        AdminRole.SUPER_ADMIN.authority(), AdminRole.EXPERT.authority())
                .requestMatchers(HttpMethod.DELETE, "/api/admin/operation-logs").hasAuthority(AdminRole.SUPER_ADMIN.authority())
                .requestMatchers("/api/admin/operation-logs").hasAnyAuthority(
                        AdminRole.SUPER_ADMIN.authority(), AdminRole.EXPERT.authority())
                // 销户不可恢复，且会连带清掉该农户的帮扶台账与农事记录 —— 只交给超级管理员
                .requestMatchers(HttpMethod.DELETE, "/api/admin/users/**").hasAuthority(AdminRole.SUPER_ADMIN.authority())
                // ===== 村镇基础数据 =====
                // 改：超管 + 村镇管理员（只能改本村，由 service 的 assertVillageInScope 兜底）
                .requestMatchers(HttpMethod.POST, "/api/admin/villages").hasAnyAuthority(
                        AdminRole.SUPER_ADMIN.authority(), AdminRole.VILLAGE_ADMIN.authority())
                .requestMatchers(HttpMethod.PUT, "/api/admin/villages/**").hasAnyAuthority(
                        AdminRole.SUPER_ADMIN.authority(), AdminRole.VILLAGE_ADMIN.authority())
                // 删村镇只给超管：会连带影响该村镇的作物、建议、灾害、气象与预警，且不可恢复
                .requestMatchers(HttpMethod.DELETE, "/api/admin/villages/**").hasAuthority(
                        AdminRole.SUPER_ADMIN.authority())
                // 作物档案与灾害记录是村镇自维护的台账，三类角色都可删；越权由 service 层 scope 校验拦住
                .requestMatchers(HttpMethod.DELETE, "/api/admin/crops/**").hasAnyAuthority(
                        AdminRole.SUPER_ADMIN.authority(), AdminRole.EXPERT.authority(),
                        AdminRole.VILLAGE_ADMIN.authority())
                .requestMatchers(HttpMethod.DELETE, "/api/admin/disasters/**").hasAnyAuthority(
                        AdminRole.SUPER_ADMIN.authority(), AdminRole.EXPERT.authority(),
                        AdminRole.VILLAGE_ADMIN.authority())
                // 其余业务数据的删除：超管+专家，村镇管理员不能删
                .requestMatchers(HttpMethod.DELETE, "/api/admin/**").hasAnyAuthority(
                        AdminRole.SUPER_ADMIN.authority(), AdminRole.EXPERT.authority())
                .requestMatchers("/api/admin/**").authenticated()
                .anyRequest().permitAll()                                   // 其余请求放行
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class) // 在用户名密码过滤器前插入JWT过滤器
            // 未认证访问受保护接口：返回 401 JSON（而非默认 403），前端据此触发自动重新登录
            .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"未登录或登录已过期，请重新登录\"}");
            }));

        return http.build();
    }

    /**
     * 配置CORS跨域策略
     * <p>
     * 允许本地开发地址（localhost和127.0.0.1的所有端口）跨域访问，
     * 支持常用HTTP方法和所有请求头，允许携带凭证，预检缓存1小时。
     * </p>
     *
     * @return CorsConfigurationSource跨域配置源
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 开发环境允许所有来源（包括局域网真机调试）；上线后改为具体域名
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS")); // 允许的HTTP方法
        config.setAllowedHeaders(List.of("*")); // 允许所有请求头
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition")); // 暴露响应头
        config.setAllowCredentials(true); // 允许携带Cookie等凭证
        config.setMaxAge(3600L); // 预检请求缓存时间：1小时

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config); // CORS规则对所有路径生效（包括/api和/uploads）
        return source;
    }

    /**
     * 密码编码器Bean
     * <p>
     * 使用BCrypt算法进行密码哈希，用于管理员密码的加密存储与验证。
     * </p>
     *
     * @return BCryptPasswordEncoder实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
