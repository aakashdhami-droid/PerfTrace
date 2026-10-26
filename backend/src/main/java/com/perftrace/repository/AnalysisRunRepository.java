package com.perftrace.repository;

import com.perftrace.entity.AnalysisRun;
import com.perftrace.entity.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, Long> {
    Page<AnalysisRun> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Optional<AnalysisRun> findByIdAndUserId(Long id, Long userId);
    long countByUserIdAndStatus(Long userId, RunStatus status);
}
