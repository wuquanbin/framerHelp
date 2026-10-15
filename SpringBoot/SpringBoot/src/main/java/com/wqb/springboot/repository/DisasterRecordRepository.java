package com.wqb.springboot.repository;

import com.wqb.springboot.entity.DisasterRecord;
import com.wqb.springboot.entity.Village;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisasterRecordRepository extends JpaRepository<DisasterRecord, Long> {

    /** 村镇管理员的数据范围：只取本村镇（village.code）的灾害记录 */
    Page<DisasterRecord> findByVillage_Code(String villageCode, Pageable pageable);

    List<DisasterRecord> findByVillageOrderBySeverityDescIdAsc(Village village);

    long countByVillage(Village village);

    void deleteByVillage(Village village);
}
