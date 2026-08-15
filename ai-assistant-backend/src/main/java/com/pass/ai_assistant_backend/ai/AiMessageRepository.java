package com.pass.ai_assistant_backend.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {

    List<AiMessage> findByConversationIdOrderBySortOrderAscIdAsc(Long conversationId);

    long countByConversationId(Long conversationId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AiMessage m where m.conversationId = :conversationId")
    void deleteByConversationId(@Param("conversationId") Long conversationId);
}
