package com.wqb.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 用户个人种植的作物
 * <p>
 * 每个微信用户勾选的作物各占一行，记录该用户自己的种植面积（亩）。
 * 与作物档案表（crop_profile，村/镇级总体数据）相互独立——
 * 用户个人面积只存这张表，页面展示时不要回退到总体档案的面积。
 * </p>
 */
@Entity
@Table(name = "user_crop",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "crop_name"}))
public class UserCrop extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户 id（app_user.id） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 作物名称（与 crop_profile.crop_name 对应，冗余存储便于按名称读写） */
    @Column(name = "crop_name", nullable = false, length = 64)
    private String cropName;

    /** 用户个人种植面积（亩）；null 表示尚未填写 */
    @Column(name = "area_mu")
    private Double areaMu;

    public UserCrop() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getCropName() { return cropName; }
    public void setCropName(String cropName) { this.cropName = cropName; }
    public Double getAreaMu() { return areaMu; }
    public void setAreaMu(Double areaMu) { this.areaMu = areaMu; }
}
