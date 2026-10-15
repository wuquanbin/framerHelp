package com.wqb.springboot.repository;

import com.wqb.springboot.entity.FarmReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 额外继承 {@link JpaSpecificationExecutor}：农情上报列表要同时按
 * 状态 / 类型 / 数据范围（村镇管理员只看本村镇）三个条件任意组合过滤。
 */
public interface FarmReportRepository extends JpaRepository<FarmReport, Long>,
        JpaSpecificationExecutor<FarmReport> {
    List<FarmReport> findByVillageCodeOrderByCreatedAtDesc(String villageCode);
    List<FarmReport> findByVillageCodeAndReportTypeOrderByCreatedAtDesc(String villageCode, String reportType);
    List<FarmReport> findByReportTypeOrderByCreatedAtDesc(String reportType);
    List<FarmReport> findByStatusOrderByCreatedAtDesc(String status);
    List<FarmReport> findAllByOrderByCreatedAtDesc();
    long countByStatus(String status);
    List<FarmReport> findByOpenidOrderByCreatedAtDesc(String openid);

    /** 管理端分页：按状态过滤（配合 Pageable 的排序参数） */
    Page<FarmReport> findByStatus(String status, Pageable pageable);

    /** 管理端分页：按类型过滤 */
    Page<FarmReport> findByReportType(String reportType, Pageable pageable);

    /** 管理端分页：状态 + 类型组合过滤 */
    Page<FarmReport> findByStatusAndReportType(String status, String reportType, Pageable pageable);

    /**
     * 帮扶成效看板：帮扶需求按作物分布（作物名取自上报时填写的 cropName）。
     *
     * @return [作物名, 上报数]，按数量倒序
     */
    @Query("SELECT r.cropName, COUNT(r) FROM FarmReport r " +
            "WHERE r.cropName IS NOT NULL AND r.cropName <> '' " +
            "GROUP BY r.cropName ORDER BY COUNT(r) DESC")
    List<Object[]> countGroupByCropName();

    /**
     * 帮扶成效看板：农情上报的「首次响应时长」统计。
     * <p>
     * 口径：上报创建时间 → 该上报下第一条帮扶响应的创建时间；只统计「至少被响应过一次」的上报，
     * 没响应的不参与平均（否则「一直没人管」会被算成 0 小时）。用原生 SQL 是因为
     * TIMESTAMPDIFF + 子查询首响时间用 JPQL 写不出来。
     * </p>
     *
     * @return 首行 [平均分钟数, 已响应上报数, 24 小时内响应数]，无数据返回空列表。
     * Hibernate 7 下单值 {@code Object[]} 会被再包一层（Object[1]{Object[3]}），所以统一走 List 取首行。
     */
    @Query(value = """
            SELECT
                AVG(TIMESTAMPDIFF(MINUTE, r.created_at, f.first_at)),
                COUNT(*),
                SUM(CASE WHEN TIMESTAMPDIFF(MINUTE, r.created_at, f.first_at) <= 1440 THEN 1 ELSE 0 END)
            FROM farm_report r
            JOIN (SELECT report_id, MIN(created_at) AS first_at
                  FROM farm_response GROUP BY report_id) f
              ON f.report_id = r.id
            """, nativeQuery = true)
    List<Object[]> firstResponseStatsRows();

    /**
     * 帮扶成效看板「最近帮扶动态」用：给定一批上报 id，返回每条上报的首次响应耗时（分钟）。
     * 只返回至少被响应过一次的上报，未响应的不出现在结果里（调用方按缺省处理为 null）。
     *
     * @return 每行 [report_id, 首次响应分钟数]
     */
    @Query(value = """
            SELECT f.report_id, TIMESTAMPDIFF(MINUTE, r.created_at, f.first_at)
            FROM farm_report r
            JOIN (SELECT report_id, MIN(created_at) AS first_at
                  FROM farm_response GROUP BY report_id) f
              ON f.report_id = r.id
            WHERE f.report_id IN :ids
            """, nativeQuery = true)
    List<Object[]> firstResponseMinutesByReportIds(@Param("ids") Collection<Long> ids);
}
