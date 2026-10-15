package com.wqb.springboot.repository;

import com.wqb.springboot.entity.FarmAdvice;
import com.wqb.springboot.entity.Village;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FarmAdviceRepository extends JpaRepository<FarmAdvice, Long> {

    /** 村镇管理员的数据范围：只取本村镇（village.code）的农事建议 */
    Page<FarmAdvice> findByVillage_Code(String villageCode, Pageable pageable);

    List<FarmAdvice> findByVillageOrderByPriorityDescIdAsc(Village village);

    long countByVillage(Village village);

    void deleteByVillage(Village village);
}
