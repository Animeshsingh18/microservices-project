package com.example.userservice.repository;

import com.example.userservice.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, java.util.UUID> {


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboxEvent o where o.published = false and o.attemptCount < :maxAttempts order by o.createdAt asc")
    List<OutboxEvent> findUnpublishedBatch(int maxAttempts);
}
