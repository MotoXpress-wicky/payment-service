package com.ecommerce.paymentservice.repository;

import com.ecommerce.paymentservice.entity.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** The events that wait, oldest first. The sequence matters: a later event must not overtake an older one. */
    @Query("select e from OutboxEvent e where e.sentAt is null order by e.createdAt asc, e.id asc")
    List<OutboxEvent> findWaiting(Pageable page);

    @Modifying
    @Query("update OutboxEvent e set e.sentAt = :now, e.lastError = null where e.id = :id and e.sentAt is null")
    int markSent(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("update OutboxEvent e set e.attempts = e.attempts + 1, e.lastError = :error where e.id = :id and e.sentAt is null")
    int markFailed(@Param("id") UUID id, @Param("error") String error);

    @Modifying
    @Query("delete from OutboxEvent e where e.sentAt is not null and e.sentAt < :before")
    int deleteSentBefore(@Param("before") Instant before);

    long countBySentAtIsNull();
}
