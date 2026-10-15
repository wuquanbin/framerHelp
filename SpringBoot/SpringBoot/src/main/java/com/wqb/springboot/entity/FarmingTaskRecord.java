package com.wqb.springboot.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 农事任务执行记录（农事闭环的「记录」环节）：7:30 推送当日任务 → 农户在小程序打卡 → 落一条记录。
 * <p>
 * 同一用户同一天同一任务只落一条（唯一索引 openid + crop_name + task_name + task_date）。
 * 记录只在查询今日任务时「懒物化」（存在即复用，不存在即创建 pending）：推送只发给开了通知的用户，
 * 而任务列表任何登录用户都会看，这样能保证「看得见的任务才有记录」，也不给不发通知的用户白建行。
 * </p>
 */
@Entity
@Table(name = "farming_task_record", indexes = {
        @Index(name = "idx_ftr_openid_date", columnList = "openid,taskDate"),
        @Index(name = "idx_ftr_task_date", columnList = "taskDate")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_ftr_task", columnNames = {"openid", "cropName", "taskName", "taskDate"})
})
public class FarmingTaskRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 openid（与 user 表对应，不用外键：微信 openid 是天然业务键） */
    @Column(nullable = false, length = 64)
    private String openid;

    @Column(nullable = false, length = 32)
    private String cropName;

    @Column(nullable = false, length = 64)
    private String taskName;

    /** 任务描述（物化时快照，知识库日后改版不影响历史记录） */
    @Column(length = 512)
    private String description;

    /** 任务在作物日历上的目标天（种植第 N 天） */
    @Column(nullable = false)
    private Integer targetDay;

    /** 任务所属日期（自然日，与 openid+crop+task 组成唯一键） */
    @Column(nullable = false)
    private LocalDate taskDate;

    /** pending / completed */
    @Column(nullable = false, length = 16)
    private String status = "pending";

    private LocalDateTime completedAt;

    /** 优先级快照（high/medium/low，用于列表排序和后台统计） */
    @Column(length = 8)
    private String priority;

    public FarmingTaskRecord() {
    }

    public FarmingTaskRecord(String openid, String cropName, String taskName, String description,
                             Integer targetDay, LocalDate taskDate, String priority) {
        this.openid = openid;
        this.cropName = cropName;
        this.taskName = taskName;
        this.description = description;
        this.targetDay = targetDay;
        this.taskDate = taskDate;
        this.priority = priority;
        this.status = "pending";
    }

    public Long getId() { return id; }
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public String getCropName() { return cropName; }
    public void setCropName(String cropName) { this.cropName = cropName; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getTargetDay() { return targetDay; }
    public void setTargetDay(Integer targetDay) { this.targetDay = targetDay; }
    public LocalDate getTaskDate() { return taskDate; }
    public void setTaskDate(LocalDate taskDate) { this.taskDate = taskDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
}
