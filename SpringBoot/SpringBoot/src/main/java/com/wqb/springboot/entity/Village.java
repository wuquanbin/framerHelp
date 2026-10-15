package com.wqb.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "village")
public class Village extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(nullable = false, length = 128)
    private String mainCrops;

    @Column(length = 255)
    private String terrain;

    @Column(length = 64)
    private String assistanceLevel;

    /** 村镇纬度（地图选址时保存，供 Open-Meteo 精确查询） */
    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    /** 村镇经度（地图选址时保存，供 Open-Meteo 精确查询） */
    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    /** 高德行政区编码（地图选址时保存，供高德天气精确查询；高德POI搜索可能不返回，为空时按市级兜底） */
    @Column(length = 16)
    private String adcode;

    public Village() {
    }

    public Village(String code, String name, String mainCrops, String terrain, String assistanceLevel) {
        this.code = code;
        this.name = name;
        this.mainCrops = mainCrops;
        this.terrain = terrain;
        this.assistanceLevel = assistanceLevel;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMainCrops() { return mainCrops; }
    public void setMainCrops(String mainCrops) { this.mainCrops = mainCrops; }
    public String getTerrain() { return terrain; }
    public void setTerrain(String terrain) { this.terrain = terrain; }
    public String getAssistanceLevel() { return assistanceLevel; }
    public void setAssistanceLevel(String assistanceLevel) { this.assistanceLevel = assistanceLevel; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public String getAdcode() { return adcode; }
    public void setAdcode(String adcode) { this.adcode = adcode; }
}
