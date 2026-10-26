package com.perftrace.repository;

import com.perftrace.entity.TelemetrySample;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TelemetrySampleRepository extends JpaRepository<TelemetrySample, Long> {

    Page<TelemetrySample> findByRunIdOrderByTimeOffsetSeconds(Long runId, Pageable pageable);

    /**
     * Downsampled time-series: picks every Nth sample for chart rendering.
     * This avoids sending tens of thousands of data points to the frontend.
     */
    @Query("SELECT s FROM TelemetrySample s WHERE s.run.id = :runId ORDER BY s.timeOffsetSeconds")
    List<TelemetrySample> findAllByRunId(@Param("runId") Long runId);

    @Query("SELECT s FROM TelemetrySample s WHERE s.run.id = :runId " +
           "AND s.timeOffsetSeconds >= :startOffset AND s.timeOffsetSeconds < :endOffset " +
           "ORDER BY s.timeOffsetSeconds")
    List<TelemetrySample> findByRunIdAndTimeRange(
        @Param("runId") Long runId,
        @Param("startOffset") double startOffset,
        @Param("endOffset") double endOffset
    );

    long countByRunId(Long runId);

    @Modifying
    @Query("DELETE FROM TelemetrySample s WHERE s.run.id = :runId")
    void deleteByRunId(@Param("runId") Long runId);
}
