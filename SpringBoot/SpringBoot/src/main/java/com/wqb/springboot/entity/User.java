package com.wqb.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_user")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String openid;

    @Column(length = 64)
    private String nickname;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String avatarUrl;

    @Column(name = "village_code", length = 32)
    private String villageCode;

    @Column(length = 11)
    private String phone;

    @Column(name = "my_crops", length = 500)
    private String myCrops;

    @Column(name = "crop_planting_dates", length = 1000)
    private String cropPlantingDates;

    @Column(nullable = false)
    private Integer status = 1;

    private LocalDateTime lastLoginTime;

    // 通知订阅设置
    @Column(nullable = false)
    private Boolean notifyWarning = true; // 灾害预警推送

    @Column(nullable = false)
    private Boolean notifyFarming = true; // 农事提醒通知

    /** 上次查看"我的回复"的时间，用于统计未读回复数；为空表示从未查看过 */
    private LocalDateTime lastReadResponseAt;

    public User() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getVillageCode() { return villageCode; }
    public void setVillageCode(String villageCode) { this.villageCode = villageCode; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getMyCrops() { return myCrops; }
    public void setMyCrops(String myCrops) { this.myCrops = myCrops; }
    public String getCropPlantingDates() { return cropPlantingDates; }
    public void setCropPlantingDates(String cropPlantingDates) { this.cropPlantingDates = cropPlantingDates; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getLastLoginTime() { return lastLoginTime; }
    public void setLastLoginTime(LocalDateTime lastLoginTime) { this.lastLoginTime = lastLoginTime; }
    public Boolean getNotifyWarning() { return notifyWarning; }
    public void setNotifyWarning(Boolean notifyWarning) { this.notifyWarning = notifyWarning; }
    public Boolean getNotifyFarming() { return notifyFarming; }
    public void setNotifyFarming(Boolean notifyFarming) { this.notifyFarming = notifyFarming; }
    public LocalDateTime getLastReadResponseAt() { return lastReadResponseAt; }
    public void setLastReadResponseAt(LocalDateTime lastReadResponseAt) { this.lastReadResponseAt = lastReadResponseAt; }
}
