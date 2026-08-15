package com.pass.ai_assistant_backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserProjectRepository extends JpaRepository<UserProject, Long> {

    List<UserProject> findByEmailOrderByLastOpenedAtDesc(String email);

    Optional<UserProject> findByProjectIdAndEmail(String projectId, String email);

    Optional<UserProject> findByProjectId(String projectId);

    boolean existsByEmailAndNameIgnoreCase(String email, String name);
}
