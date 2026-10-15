package com.wqb.springboot.repository;

import com.wqb.springboot.entity.AiGenerationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiGenerationRecordRepository extends JpaRepository<AiGenerationRecord, Long> {

    /** 控制台左侧历史列表：取最近 50 条（不分页，够用且省一次 count 查询） */
    List<AiGenerationRecord> findTop50ByOrderByCreatedAtDesc();
}
