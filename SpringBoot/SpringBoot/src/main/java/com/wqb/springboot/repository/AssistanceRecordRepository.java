package com.wqb.springboot.repository;

import com.wqb.springboot.entity.AssistanceRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wqb.springboot.entity.User;

import java.time.LocalDateTime;
import java.util.List;

public interface AssistanceRecordRepository extends JpaRepository<AssistanceRecord, Long> {

    /** 村镇管理员的数据范围：台账按被帮扶农户的 villageCode 归属 */
    Page<AssistanceRecord> findByUser_VillageCode(String villageCode, Pageable pageable);

    List<AssistanceRecord> findByUserId(Long userId);

    List<AssistanceRecord> findByStatus(String status);

    /** 某条农情上报是否已登记台账，用于手工/自动建档防重 */
    boolean existsByReportId(Long reportId);

    void deleteByUser(User user);

    /** 帮扶成效看板：被帮扶过的农户数（按用户去重） */
    @Query("SELECT COUNT(DISTINCT r.user.id) FROM AssistanceRecord r")
    long countDistinctUsers();

    /** 帮扶成效看板：某时刻之后新增的帮扶记录数（本月新增用） */
    long countByCreatedAtGreaterThanEqual(LocalDateTime time);

    /** 帮扶成效看板：按帮扶类型分布 */
    @Query("SELECT r.assistanceType, COUNT(r) FROM AssistanceRecord r GROUP BY r.assistanceType ORDER BY COUNT(r) DESC")
    List<Object[]> countGroupByType();

    /** 帮扶成效看板：按村镇分布（经 user.villageCode 归属，未归属的归为 null） */
    @Query("SELECT r.user.villageCode, COUNT(r) FROM AssistanceRecord r GROUP BY r.user.villageCode ORDER BY COUNT(r) DESC")
    List<Object[]> countGroupByVillageCode();

    /** 帮扶成效看板：最近 N 天每天的帮扶记录数（趋势曲线用） */
    @Query("SELECT FUNCTION('DATE', r.createdAt), COUNT(r) FROM AssistanceRecord r " +
            "WHERE r.createdAt >= :since GROUP BY FUNCTION('DATE', r.createdAt) ORDER BY FUNCTION('DATE', r.createdAt)")
    List<Object[]> countByDateSince(@Param("since") LocalDateTime since);
}
