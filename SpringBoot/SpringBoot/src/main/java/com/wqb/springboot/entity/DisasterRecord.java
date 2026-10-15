package com.wqb.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "disaster_record")
public class DisasterRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "village_id", nullable = false)
    private Village village;

    @Column(nullable = false, length = 128)
    private String disasterName;

    @Column(nullable = false, length = 128)
    private String affectedArea;

    @Column(nullable = false, length = 32)
    private String disasterType;

    @Column(nullable = false, length = 128)
    private String triggerWeather;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String remedy;

    @Column(nullable = false)
    private Integer severity;

    public DisasterRecord() {
    }

    public DisasterRecord(
            Village village,
            String disasterName,
            String affectedArea,
            String disasterType,
            String triggerWeather,
            String description,
            String remedy,
            Integer severity
    ) {
        this.village = village;
        this.disasterName = disasterName;
        this.affectedArea = affectedArea;
        this.disasterType = disasterType;
        this.triggerWeather = triggerWeather;
        this.description = description;
        this.remedy = remedy;
        this.severity = severity;
    }

    public Long getId() { return id; }
    public Village getVillage() { return village; }
    public void setVillage(Village village) { this.village = village; }
    public String getDisasterName() { return disasterName; }
    public void setDisasterName(String disasterName) { this.disasterName = disasterName; }
    public String getAffectedArea() { return affectedArea; }
    public void setAffectedArea(String affectedArea) { this.affectedArea = affectedArea; }
    public String getDisasterType() { return disasterType; }
    public void setDisasterType(String disasterType) { this.disasterType = disasterType; }
    public String getTriggerWeather() { return triggerWeather; }
    public void setTriggerWeather(String triggerWeather) { this.triggerWeather = triggerWeather; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRemedy() { return remedy; }
    public void setRemedy(String remedy) { this.remedy = remedy; }
    public Integer getSeverity() { return severity; }
    public void setSeverity(Integer severity) { this.severity = severity; }
}
