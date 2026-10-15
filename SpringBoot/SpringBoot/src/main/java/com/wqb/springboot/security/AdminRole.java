package com.wqb.springboot.security;

import java.util.Set;

/**
 * 后台管理员角色（三级权限模型）。
 * <p>
 * <b>超级管理员</b>：管账号、管参数、看审计日志，也能删数据；
 * <b>专家</b>：看农情、给建议、接人工咨询，不碰账号与系统参数；
 * <b>村镇管理员</b>：只处理本村镇业务，不能删历史数据。
 * 角色同时决定<b>功能权限</b>（能调哪些接口，见 {@code SecurityConfig}）和
 * <b>数据范围</b>（村镇管理员只能看到绑定村镇的数据）。
 * </p>
 * <p>
 * 兼容说明：历史库里可能残留 DATA_ADMIN / CONTENT_ADMIN 两个旧角色，
 * {@link #normalize(String)} 会把它们降级映射到 EXPERT，避免出现「既不是任何一个角色、又能通过认证」的中间态。
 * </p>
 */
public enum AdminRole {

    /** 超级管理员：唯一能管理账号、系统参数、清空操作日志的角色 */
    SUPER_ADMIN("超级管理员"),
    /** 专家：农技专家，业务数据读写 + 人工客服，无账号/系统管理权 */
    EXPERT("专家"),
    /** 村镇管理员：绑定单个村镇，只处理本村镇业务，无删除权 */
    VILLAGE_ADMIN("村镇管理员");

    private final String label;

    AdminRole(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Spring Security 里使用的权限字符串（JWT 过滤器授予的就是这个） */
    public String authority() {
        return "ROLE_" + name();
    }

    /** 能进「系统设置 / 人员管理 / 操作日志」的角色 */
    public static final Set<String> SYSTEM_ROLES = Set.of(SUPER_ADMIN.name());

    /** 能删除业务数据的角色（村镇管理员刻意排除：一线人员误删历史台账代价太大） */
    public static final Set<String> DELETE_ROLES = Set.of(SUPER_ADMIN.name(), EXPERT.name());

    /**
     * 归一化角色字符串：未知/旧角色统一降级为 EXPERT。
     * <p>
     * 降级而不是报错，是因为角色来自数据库里的一个字符串列 ——
     * 万一被人手工改坏，宁可让账号以最小权限登录，也不要整个后台打不开。
     * </p>
     *
     * @param raw 数据库中的角色值，可为空
     * @return 归一化后的角色，永不为 null
     */
    public static AdminRole normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return EXPERT;
        }
        String upper = raw.trim().toUpperCase();
        return switch (upper) {
            case "SUPER_ADMIN" -> SUPER_ADMIN;
            case "EXPERT" -> EXPERT;
            case "VILLAGE_ADMIN" -> VILLAGE_ADMIN;
            // ADMIN 是改造前超级管理员用的值。这里必须映射成 SUPER_ADMIN 而不是降级：
            // 库里现存的管理员账号就是这个值，降级会把唯一能管账号的人锁在外面。
            case "ADMIN" -> SUPER_ADMIN;
            // DATA_ADMIN / CONTENT_ADMIN 是改造前的旧角色，按最小权限原则并入专家
            default -> EXPERT;
        };
    }

    /** 该角色是否需要绑定村镇（村镇管理员必须绑定，其他人绑了也不生效） */
    public boolean requiresVillage() {
        return this == VILLAGE_ADMIN;
    }
}
