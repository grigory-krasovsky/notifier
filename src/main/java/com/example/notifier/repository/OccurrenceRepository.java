package com.example.notifier.repository;

import com.example.notifier.domain.Occurrence;
import com.example.notifier.domain.OccurrenceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OccurrenceRepository extends JpaRepository<Occurrence, Long> {

	/** Scheduler poll: open occurrences whose reminder is due. */
	List<Occurrence> findByStatusAndNextReminderAtLessThanEqual(OccurrenceStatus status, Instant moment);

	Optional<Occurrence> findByEventIdAndStatus(Long eventId, OccurrenceStatus status);

	/** Open occurrences across all of a user's events — used to show the next reminder in /schedule and /manage. */
	List<Occurrence> findByEvent_User_IdAndStatus(Long userId, OccurrenceStatus status);

	void deleteByEventId(Long eventId);
}
