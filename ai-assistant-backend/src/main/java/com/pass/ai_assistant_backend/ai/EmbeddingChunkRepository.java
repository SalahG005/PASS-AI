package com.pass.ai_assistant_backend.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EmbeddingChunkRepository extends JpaRepository<EmbeddingChunk, Long> {

    List<EmbeddingChunk> findByEmailAndWorkspaceId(String email, String workspaceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EmbeddingChunk e where e.email = :email and e.workspaceId = :workspaceId")
    void deleteByEmailAndWorkspaceId(@Param("email") String email, @Param("workspaceId") String workspaceId);

    long countByEmailAndWorkspaceId(String email, String workspaceId);
}
