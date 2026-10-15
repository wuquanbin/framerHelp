package com.wqb.springboot.repository;

import com.wqb.springboot.entity.Village;
import com.wqb.springboot.entity.WarningNotice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WarningNoticeRepository extends JpaRepository<WarningNotice, Long> {

    List<WarningNotice> findByStatus(String status);
    long countByStatus(String status);
    List<WarningNotice> findByVillage(Village village);
    List<WarningNotice> findByVillageAndStatus(Village village, String status);

    long countByVillage(Village village);

    void deleteByVillage(Village village);

    /**
     * 查询仍处于有效期的预警。
     * <p>只用 status 过滤的话，历史/演示数据里 expiresAt 早已过去的「僵尸预警」会出现在首页、卡片和推送里，
     * 所以统一按 expiresAt 过滤：为空视为无期限，否则必须晚于当前时间。</p>
     *
     * @param status 状态，一般为 active
     * @param now    当前时间
     * @return 仍在有效期内的预警
     */
    @Query("select w from WarningNotice w where w.status = :status and (w.expiresAt is null or w.expiresAt > :now)")
    List<WarningNotice> findEffectiveByStatus(@Param("status") String status, @Param("now") LocalDateTime now);

    /** 有效期内预警数量，用于首页 KPI，口径与 {@link #findEffectiveByStatus} 一致 */
    @Query("select count(w) from WarningNotice w where w.status = :status and (w.expiresAt is null or w.expiresAt > :now)")
    long countEffectiveByStatus(@Param("status") String status, @Param("now") LocalDateTime now);

    /**
     * 删除所有已过期的预警（不限于某个村镇）。
     * <p>
     * 必须按整表清理：历史遗留/演示数据的 village_id 可能为 NULL，
     * 用 findByVillage 查不到，会导致过期预警永远残留。
     * </p>
     *
     * @param time 当前时间
     * @return 删除条数
     */
    long deleteByExpiresAtBefore(LocalDateTime time);
}
