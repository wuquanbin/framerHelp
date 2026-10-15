package com.wqb.springboot.repository;

import com.wqb.springboot.entity.HumanChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HumanChatMessageRepository extends JpaRepository<HumanChatMessage, Long> {

    List<HumanChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    List<HumanChatMessage> findBySessionIdAndSenderTypeAndReadFlagFalse(Long sessionId, String senderType);

    long countBySessionIdAndSenderTypeAndReadFlagFalse(Long sessionId, String senderType);
}
