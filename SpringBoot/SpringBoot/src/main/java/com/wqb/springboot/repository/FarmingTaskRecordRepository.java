package com.wqb.springboot.repository;

import com.wqb.springboot.entity.FarmingTaskRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FarmingTaskRecordRepository extends JpaRepository<FarmingTaskRecord, Long> {

    /** 懒物化查重：同一用户同一天同一任务只落一条 */
    Optional<FarmingTaskRecord> findByOpenidAndCropNameAndTaskNameAndTaskDate(
            String openid, String cropName, String taskName, LocalDate taskDate);

    /** 某用户某天的全部任务记录（按优先级高 → 低排序） */
    @Query("SELECT r FROM FarmingTaskRecord r WHERE r.openid = :openid AND r.taskDate = :date " +
            "ORDER BY CASE r.priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END, r.id")
    List<FarmingTaskRecord> findTodayTasks(@Param("openid") String openid, @Param("date") LocalDate date);

    long countByTaskDate(LocalDate date);

    long countByTaskDateAndStatus(LocalDate date, String status);

    /** 后台统计：某天各作物的任务数与完成数 */
    @Query("SELECT r.cropName, COUNT(r), SUM(CASE WHEN r.status = 'completed' THEN 1 ELSE 0 END) " +
            "FROM FarmingTaskRecord r WHERE r.taskDate = :date GROUP BY r.cropName")
    List<Object[]> countByCropOnDate(@Param("date") LocalDate date);

    /** 后台统计：最近 N 天每天的总量/完成量（完成率曲线用） */
    @Query("SELECT r.taskDate, COUNT(r), SUM(CASE WHEN r.status = 'completed' THEN 1 ELSE 0 END) " +
            "FROM FarmingTaskRecord r WHERE r.taskDate >= :since GROUP BY r.taskDate ORDER BY r.taskDate")
    List<Object[]> countByDateSince(@Param("since") LocalDate since);

    /**
     * 农事档案：某用户一段时间内的全部任务记录（日期倒序、同日按优先级）。
     * <p>记录是「查看今日任务时懒物化」的，没打开过小程序的日子不会有行，档案里那几天就是空白 —— 这是刻意的。</p>
     */
    @Query("SELECT r FROM FarmingTaskRecord r WHERE r.openid = :openid AND r.taskDate BETWEEN :from AND :to " +
            "ORDER BY r.taskDate DESC, CASE r.priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END, r.id")
    List<FarmingTaskRecord> findArchive(@Param("openid") String openid,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);

    /**
     * 注销用户时清掉其农事任务记录。
     * <p>
     * 本表按 openid 关联（不是外键），不会随用户删除级联 ——
     * 不清的话这个 openid 下次重新注册，会看到上一个主人的打卡历史。
     * </p>
     */
    void deleteByOpenid(String openid);
}
