package com.wqb.springboot.repository;

import com.wqb.springboot.entity.HumanChatSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HumanChatSessionRepository extends JpaRepository<HumanChatSession, Long> {

    /** 管理员看所有会话：按最后消息时间倒序 */
    List<HumanChatSession> findAllByOrderByLastMessageTimeDesc();

    /** 农民看自己的会话列表 */
    List<HumanChatSession> findByFarmerOpenidOrderByLastMessageTimeDesc(String farmerOpenid);

    /** 找某个农民当前打开中的会话（WAITING / ACTIVE），取最新 1 条 */
    @Query("SELECT s FROM HumanChatSession s WHERE s.farmerOpenid = :openid AND s.status IN ('WAITING','ACTIVE') ORDER BY s.createdAt DESC")
    List<HumanChatSession> findActiveByFarmerOpenidList(@Param("openid") String openid, Pageable pageable);

    default Optional<HumanChatSession> findActiveByFarmerOpenid(String openid) {
        List<HumanChatSession> list = findActiveByFarmerOpenidList(openid, Pageable.ofSize(1));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 按状态过滤（管理员侧） */
    List<HumanChatSession> findByStatusInOrderByLastMessageTimeDesc(List<String> statuses);
}
