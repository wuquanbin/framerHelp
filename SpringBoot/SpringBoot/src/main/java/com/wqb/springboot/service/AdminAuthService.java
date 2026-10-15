package com.wqb.springboot.service;

import com.wqb.springboot.dto.AuthDto;

import java.util.Map;

/**
 * 管理员认证服务接口
 * <p>
 * 提供管理员登录认证和当前登录管理员信息查询功能。
 * </p>
 */
public interface AdminAuthService {

    /**
     * 管理员登录
     * <p>
     * 根据用户名和密码验证身份,验证通过后生成 JWT 令牌并返回登录信息。
     * </p>
     *
     * @param request 登录请求,包含用户名和密码
     * @return 登录响应,包含 token、用户名、真实姓名和角色
     */
    AuthDto.LoginResponse login(AuthDto.LoginRequest request);

    /**
     * 获取管理员信息
     *
     * @param username 管理员用户名
     * @return 管理员信息,包含 username、realName、role、phone、email
     */
    Map<String, Object> getAdminInfo(String username);
}
