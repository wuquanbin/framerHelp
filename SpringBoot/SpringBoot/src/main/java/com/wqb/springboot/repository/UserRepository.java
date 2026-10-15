package com.wqb.springboot.repository;

import com.wqb.springboot.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 额外继承 {@link JpaSpecificationExecutor}：后台农户列表的关键词/状态/村镇筛选
 * 需要动态拼条件（三个条件任意组合），用派生查询方法会组合爆炸。
 */
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByOpenid(String openid);
    List<User> findByOpenidIn(List<String> openids);
    long countByStatus(Integer status);
    boolean existsByOpenid(String openid);
    List<User> findByNickname(String nickname);
    List<User> findByVillageCode(String villageCode);

    /** 村镇管理员的数据范围：只取本村镇的农户（分页版） */
    Page<User> findByVillageCode(String villageCode, Pageable pageable);
}
