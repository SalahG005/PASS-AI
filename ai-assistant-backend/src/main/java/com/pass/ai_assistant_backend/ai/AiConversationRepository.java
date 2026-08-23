package com.pass.ai_assistant_backend.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    List<AiConversation> findByEmailOrderByUpdatedAtDesc(String email);

    List<AiConversation> findByEmailAndWorkspaceIdOrderByUpdatedAtDesc(String email, String workspaceId);

    Optional<AiConversation> findByIdAndEmail(Long id, String email);
}
