package com.wqb.springboot.controller;

import com.wqb.springboot.dto.ApiResponse;
import com.wqb.springboot.dto.AuthDto;
import com.wqb.springboot.service.AdminAuthService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理员认证控制器
 * <p>
 * 提供管理员登录认证和获取当前登录管理员信息的接口。
 * 业务逻辑已下沉至 {@link AdminAuthService},控制器仅负责参数校验和请求转发。
 * </p>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    /**
     * 管理员登录
     *
     * @param request 登录请求体,包含用户名和密码
     * @return 登录响应,包含token、用户名、真实姓名和角色
     */
    @PostMapping("/login")
    public ApiResponse<AuthDto.LoginResponse> login(@RequestBody AuthDto.LoginRequest request) {
        return ApiResponse.ok(adminAuthService.login(request));
    }

    /**
     * 获取当前登录管理员信息
     *
     * @return 管理员信息Map,包含用户名、真实姓名、角色、手机号和邮箱
     */
    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> adminInfo() {
        // 从安全上下文中获取当前认证用户名
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();
        return ApiResponse.ok(adminAuthService.getAdminInfo(username));
    }
}
