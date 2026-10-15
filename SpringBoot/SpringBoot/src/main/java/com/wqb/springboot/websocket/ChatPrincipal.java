package com.wqb.springboot.websocket;

import java.security.Principal;

/**
 * WebSocket 自定义 Principal
 * <p>
 * 区分农民端（FARMER，身份=openid）和管理端专家（ADMIN，身份=username），
 * 保证 convertAndSendToUser 可以准确定向推送。
 * name 字段格式：FARMER:{openid} 或 ADMIN:{username}
 * </p>
 */
public class ChatPrincipal implements Principal {

    public static final String PREFIX_FARMER = "FARMER:";
    public static final String PREFIX_ADMIN = "ADMIN:";

    private final String name;     // 组合唯一ID，用于 userDestination 匹配
    private final String role;     // FARMER / ADMIN
    private final String identity; // 真实身份（openid 或 username）

    public ChatPrincipal(String role, String identity) {
        this.role = role;
        this.identity = identity;
        this.name = ("ADMIN".equals(role) ? PREFIX_ADMIN : PREFIX_FARMER) + identity;
    }

    @Override
    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public String getIdentity() {
        return identity;
    }

    public boolean isFarmer() {
        return "FARMER".equals(role);
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
