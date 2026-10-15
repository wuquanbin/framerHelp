package com.wqb.springboot.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdminRole} 单元测试。
 * <p>
 * 角色归一化是权限模型的入口：它判断错了，轻则用户看不到菜单，
 * 重则把唯一能管账号的人降级掉（历史上 ADMIN 这个值就是这么来的）。
 * 纯函数，不需要 Spring 容器。
 * </p>
 */
@DisplayName("后台角色模型")
class AdminRoleTest {

    @ParameterizedTest
    @CsvSource({
            "SUPER_ADMIN, SUPER_ADMIN",
            "EXPERT,       EXPERT",
            "VILLAGE_ADMIN,VILLAGE_ADMIN"
    })
    @DisplayName("三级角色原样识别")
    void knownRoles(String input, AdminRole expected) {
        assertSame(expected, AdminRole.normalize(input));
    }

    @Test
    @DisplayName("ADMIN 必须映射为超级管理员：库里现存管理员账号就是这个值")
    void legacyAdminIsSuperAdmin() {
        assertSame(AdminRole.SUPER_ADMIN, AdminRole.normalize("ADMIN"));
        // 前后端共用这条规则，否则旧账号一登录就丢了系统管理权限
        assertEquals("超级管理员", AdminRole.normalize("ADMIN").getLabel());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATA_ADMIN", "CONTENT_ADMIN", "banana", ""})
    @DisplayName("未知或旧角色降级为专家，绝不返回 null")
    void unknownRolesDegradeToExpert(String input) {
        assertSame(AdminRole.EXPERT, AdminRole.normalize(input));
    }

    @Test
    @DisplayName("null 也降级为专家（角色列被清空时不能 NPE）")
    void nullRole() {
        assertSame(AdminRole.EXPERT, AdminRole.normalize(null));
    }

    @Test
    @DisplayName("大小写与空格不敏感（角色来自数据库字符串列）")
    void caseInsensitive() {
        assertSame(AdminRole.SUPER_ADMIN, AdminRole.normalize("  super_admin "));
        assertSame(AdminRole.VILLAGE_ADMIN, AdminRole.normalize("village_admin"));
    }

    @Test
    @DisplayName("权限字符串与 SecurityConfig 里的 hasAuthority 一致")
    void authorityFormat() {
        assertEquals("ROLE_SUPER_ADMIN", AdminRole.SUPER_ADMIN.authority());
        assertEquals("ROLE_EXPERT", AdminRole.EXPERT.authority());
        assertEquals("ROLE_VILLAGE_ADMIN", AdminRole.VILLAGE_ADMIN.authority());
    }

    @Test
    @DisplayName("只有村镇管理员需要绑定村镇")
    void villageBinding() {
        assertTrue(AdminRole.VILLAGE_ADMIN.requiresVillage());
        assertFalse(AdminRole.EXPERT.requiresVillage());
        assertFalse(AdminRole.SUPER_ADMIN.requiresVillage());
    }

    @Test
    @DisplayName("删除权只给超级管理员和专家，村镇管理员被排除")
    void deleteRoles() {
        assertTrue(AdminRole.DELETE_ROLES.contains(AdminRole.SUPER_ADMIN.name()));
        assertTrue(AdminRole.DELETE_ROLES.contains(AdminRole.EXPERT.name()));
        assertFalse(AdminRole.DELETE_ROLES.contains(AdminRole.VILLAGE_ADMIN.name()));
    }

    @Test
    @DisplayName("系统管理权只给超级管理员")
    void systemRoles() {
        assertTrue(AdminRole.SYSTEM_ROLES.contains(AdminRole.SUPER_ADMIN.name()));
        assertFalse(AdminRole.SYSTEM_ROLES.contains(AdminRole.EXPERT.name()));
        assertFalse(AdminRole.SYSTEM_ROLES.contains(AdminRole.VILLAGE_ADMIN.name()));
    }
}
