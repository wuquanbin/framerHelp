package com.wqb.springboot.service.impl;

import com.wqb.springboot.dto.AuthDto;
import com.wqb.springboot.entity.AdminUser;
import com.wqb.springboot.exception.BusinessException;
import com.wqb.springboot.repository.AdminUserRepository;
import com.wqb.springboot.security.AdminRole;
import com.wqb.springboot.security.JwtUtil;
import com.wqb.springboot.service.AdminAuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理员认证服务实现类
 * <p>
 * 提供管理员登录认证和当前登录管理员信息查询功能。
 * </p>
 */
@Service
public class AdminAuthServiceImpl implements AdminAuthService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AdminAuthServiceImpl(AdminUserRepository adminUserRepository,
                                PasswordEncoder passwordEncoder,
                                JwtUtil jwtUtil) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public AuthDto.LoginResponse login(AuthDto.LoginRequest request) {
        // 根据用户名查找管理员
        AdminUser admin = adminUserRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BusinessException("用户名或密码错误"));

        // 验证密码是否匹配
        if (!passwordEncoder.matches(request.getPassword(), admin.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 检查账号是否被禁用
        if (admin.getStatus() != 1) {
            throw new BusinessException("账号已被禁用");
        }

        // 更新最后登录时间
        admin.setLastLoginTime(LocalDateTime.now());
        adminUserRepository.save(admin);

        // 生成 JWT 前先归一化角色：库里的旧值（DATA_ADMIN 等）不会带进 token，否则前端菜单权限判断会全部落空。
        AdminRole role = AdminRole.normalize(admin.getRole());
        String token = jwtUtil.generateToken(admin.getUsername(), role.name());

        return new AuthDto.LoginResponse(
                token,
                admin.getUsername(),
                admin.getRealName(),
                role.name(),
                role.getLabel(),
                role.requiresVillage() ? admin.getVillageCode() : null
        );
    }

    @Override
    public Map<String, Object> getAdminInfo(String username) {
        AdminUser admin = adminUserRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        AdminRole role = AdminRole.normalize(admin.getRole());

        Map<String, Object> info = new HashMap<>();
        info.put("username", admin.getUsername());
        info.put("realName", admin.getRealName());
        info.put("role", role.name());
        info.put("roleLabel", role.getLabel());
        info.put("villageCode", role.requiresVillage() ? admin.getVillageCode() : null);
        info.put("phone", admin.getPhone());
        info.put("email", admin.getEmail());

        return info;
    }
}
