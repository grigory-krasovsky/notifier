package com.example.notifier.repository;

import com.example.notifier.domain.Event;
import com.example.notifier.domain.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

	/** Scheduler poll: events due to fire. */
	List<Event> findByStatusAndNextFireAtLessThanEqual(EventStatus status, Instant moment);

	Optional<Event> findFirstByUserIdAndStatus(Long userId, EventStatus status);

	List<Event> findByUserIdAndStatusInOrderByCreatedAt(Long userId, Collection<EventStatus> statuses);

	/** /finished list: completed series, newest completion first. */
	List<Event> findByUserIdAndStatusOrderByFinishedAtDesc(Long userId, EventStatus status);
}
