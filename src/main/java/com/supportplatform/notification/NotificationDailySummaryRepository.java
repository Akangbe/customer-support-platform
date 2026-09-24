package com.supportplatform.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface NotificationDailySummaryRepository extends JpaRepository<NotificationDailySummary, UUID> {

    /** The days in a range this key has already been summarized for — the catch-up's "already done" set. */
    @Query("""
            SELECT s.periodStart FROM NotificationDailySummary s
             WHERE s.apiKeyId = :apiKeyId
               AND s.periodStart >= :from
               AND s.periodStart <  :to
            """)
    List<LocalDate> findSummarizedDays(@Param("apiKeyId") UUID apiKeyId,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);
}
