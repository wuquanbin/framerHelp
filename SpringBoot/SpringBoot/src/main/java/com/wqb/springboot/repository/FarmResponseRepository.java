package com.wqb.springboot.repository;

import com.wqb.springboot.entity.FarmResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface FarmResponseRepository extends JpaRepository<FarmResponse, Long> {
    List<FarmResponse> findByReportIdOrderByCreatedAtAsc(Long reportId);
    List<FarmResponse> findByReportIdInOrderByCreatedAtDesc(List<Long> reportIds);

    /** 统计一批农情上报下的响应总数（从未查看时使用） */
    long countByReportIdIn(List<Long> reportIds);

    /** 统计一批农情上报在指定时间之后新增的管理员响应数 */
    long countByReportIdInAndCreatedAtAfter(List<Long> reportIds, LocalDateTime after);
}
