package com.example.notifier.repository;

import com.example.notifier.domain.Event;
import com.example.notifier.domain.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

	/** Scheduler poll: events due to fire. */
	List<Event> findByStatusAndNextFireAtLessThanEqual(EventStatus status, Instant moment);

	List<Event> findByUserIdAndStatusOrderByCreatedAt(Long userId, EventStatus status);
}
