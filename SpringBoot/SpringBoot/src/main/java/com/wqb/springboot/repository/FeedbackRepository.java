package com.wqb.springboot.repository;

import com.wqb.springboot.entity.Feedback;
import com.wqb.springboot.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    List<Feedback> findByStatus(String status);
    long countByStatus(String status);
    void deleteByUser(User user);
    List<Feedback> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 管理端分页：按状态过滤（配合 Pageable 的排序参数） */
    Page<Feedback> findByStatus(String status, Pageable pageable);

    /** 统计某用户所有已回复的反馈数（从未查看"我的回复"时使用） */
    long countByUserIdAndReplyIsNotNull(Long userId);

    /** 统计某用户在指定时间之后被管理员回复/更新过的已回复反馈数 */
    long countByUserIdAndReplyIsNotNullAndUpdatedAtAfter(Long userId, LocalDateTime after);
}
