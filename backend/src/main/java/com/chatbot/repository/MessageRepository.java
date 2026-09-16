package com.chatbot.repository;

import com.chatbot.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    List<MessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    long deleteBySessionId(String sessionId);
}
