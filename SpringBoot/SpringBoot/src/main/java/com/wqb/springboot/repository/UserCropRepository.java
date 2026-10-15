package com.wqb.springboot.repository;

import com.wqb.springboot.entity.UserCrop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface UserCropRepository extends JpaRepository<UserCrop, Long> {

    List<UserCrop> findByUserId(Long userId);

    Optional<UserCrop> findByUserIdAndCropName(Long userId, String cropName);

    @Transactional
    void deleteByUserIdAndCropName(Long userId, String cropName);

    /** 删除用户时清掉其个人作物（没有外键，不主动清会留下永远读不到的脏行） */
    @Transactional
    void deleteByUserId(Long userId);
}
