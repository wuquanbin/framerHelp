package com.wqb.springboot.repository;

import com.wqb.springboot.entity.CropProfile;
import com.wqb.springboot.entity.Village;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * 作物档案仓储
 * <p>
 * 后台列表页要按「村镇 + 类型」任意组合筛选，方法名派生查询得写 4 个方法，
 * 所以额外实现 {@link JpaSpecificationExecutor}，把条件留给 service 拼。
 * </p>
 */
public interface CropProfileRepository extends JpaRepository<CropProfile, Long>,
        JpaSpecificationExecutor<CropProfile> {

    /** 村镇管理员的数据范围：只取本村镇（village.code）的作物 */
    Page<CropProfile> findByVillage_Code(String villageCode, Pageable pageable);

    List<CropProfile> findByVillageOrderByRiskLevelDescIdAsc(Village village);

    List<CropProfile> findByVillageAndCropType(Village village, String cropType);

    long countByVillage(Village village);

    void deleteByVillage(Village village);
}
