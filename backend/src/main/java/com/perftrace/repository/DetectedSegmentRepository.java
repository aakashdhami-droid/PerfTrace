package com.perftrace.repository;

import com.perftrace.entity.DetectedSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DetectedSegmentRepository extends JpaRepository<DetectedSegment, Long> {

    List<DetectedSegment> findByRunIdOrderByStartOffsetSeconds(Long runId);

    @Modifying
    @Query("DELETE FROM DetectedSegment s WHERE s.run.id = :runId")
    void deleteByRunId(@Param("runId") Long runId);
}
