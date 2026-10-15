package com.wqb.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 村镇作物档案
 * <p>
 * 索引说明：查询模式为「按村镇取全部作物」或「按村镇 + 作物名取单条」，
 * 农事建议与作物管理页都会高频访问，故建 (village_id, crop_name) 联合索引。
 * </p>
 */
@Entity
@Table(
    name = "crop_profile",
    indexes = {
        @Index(name = "idx_crop_village_name", columnList = "village_id, crop_name")
    }
)
public class CropProfile extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "village_id", nullable = false)
    private Village village;

    @Column(nullable = false, length = 64)
    private String cropName;

    @Column(nullable = false, length = 32)
    private String cropType;

    @Column(nullable = false, length = 128)
    private String plantingArea;

    @Column(nullable = false, length = 64)
    private String growthStage;

    @Column(nullable = false)
    private Boolean isMainCrop;

    @Column(nullable = false, length = 128)
    private String suitableWeather;

    @Column(nullable = false, length = 128)
    private String riskWeather;

    @Column(nullable = false)
    private Integer riskLevel;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String farmingNotes;

    @Column(length = 500)
    private String imageUrl;

    public CropProfile() {
    }

    public CropProfile(
            Village village,
            String cropName,
            String cropType,
            String plantingArea,
            String growthStage,
            Boolean isMainCrop,
            String suitableWeather,
            String riskWeather,
            Integer riskLevel,
            String farmingNotes
    ) {
        this.village = village;
        this.cropName = cropName;
        this.cropType = cropType;
        this.plantingArea = plantingArea;
        this.growthStage = growthStage;
        this.isMainCrop = isMainCrop;
        this.suitableWeather = suitableWeather;
        this.riskWeather = riskWeather;
        this.riskLevel = riskLevel;
        this.farmingNotes = farmingNotes;
    }

    public Long getId() { return id; }
    public Village getVillage() { return village; }
    public void setVillage(Village village) { this.village = village; }
    public String getCropName() { return cropName; }
    public void setCropName(String cropName) { this.cropName = cropName; }
    public String getCropType() { return cropType; }
    public void setCropType(String cropType) { this.cropType = cropType; }
    public String getPlantingArea() { return plantingArea; }
    public void setPlantingArea(String plantingArea) { this.plantingArea = plantingArea; }
    public String getGrowthStage() { return growthStage; }
    public void setGrowthStage(String growthStage) { this.growthStage = growthStage; }
    public Boolean getIsMainCrop() { return isMainCrop; }
    public void setIsMainCrop(Boolean isMainCrop) { this.isMainCrop = isMainCrop; }
    public String getSuitableWeather() { return suitableWeather; }
    public void setSuitableWeather(String suitableWeather) { this.suitableWeather = suitableWeather; }
    public String getRiskWeather() { return riskWeather; }
    public void setRiskWeather(String riskWeather) { this.riskWeather = riskWeather; }
    public Integer getRiskLevel() { return riskLevel; }
    public void setRiskLevel(Integer riskLevel) { this.riskLevel = riskLevel; }
    public String getFarmingNotes() { return farmingNotes; }
    public void setFarmingNotes(String farmingNotes) { this.farmingNotes = farmingNotes; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
}
