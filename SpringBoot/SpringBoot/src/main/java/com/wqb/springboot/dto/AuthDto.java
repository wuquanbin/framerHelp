package com.wqb.springboot.dto;

public class AuthDto {

    public static class LoginRequest {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class LoginResponse {
        private String token;
        private String username;
        private String realName;
        private String role;
        /** 角色中文名（前端直接展示，避免各页面各写一份映射） */
        private String roleLabel;
        /** 数据范围：村镇管理员绑定的村镇编码，其余角色为 null */
        private String villageCode;

        public LoginResponse() {}

        public LoginResponse(String token, String username, String realName, String role,
                             String roleLabel, String villageCode) {
            this.token = token;
            this.username = username;
            this.realName = realName;
            this.role = role;
            this.roleLabel = roleLabel;
            this.villageCode = villageCode;
        }

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getRealName() { return realName; }
        public void setRealName(String realName) { this.realName = realName; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getRoleLabel() { return roleLabel; }
        public void setRoleLabel(String roleLabel) { this.roleLabel = roleLabel; }
        public String getVillageCode() { return villageCode; }
        public void setVillageCode(String villageCode) { this.villageCode = villageCode; }
    }

    public static class WechatLoginRequest {
        private String code;
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }

    public static class WechatLoginResponse {
        private String token;
        private Long userId;
        private String nickname;
        private String avatarUrl;
        private String openid;

        public WechatLoginResponse() {}

        public WechatLoginResponse(String token, Long userId, String nickname, String avatarUrl, String openid) {
            this.token = token;
            this.userId = userId;
            this.nickname = nickname;
            this.avatarUrl = avatarUrl;
            this.openid = openid;
        }

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
        public String getAvatarUrl() { return avatarUrl; }
        public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
        public String getOpenid() { return openid; }
        public void setOpenid(String openid) { this.openid = openid; }
    }
}
