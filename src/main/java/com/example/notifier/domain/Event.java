package com.example.notifier.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalTime;

@Entity
@Table(name = "event")
@Getter
@Setter
@NoArgsConstructor
public class Event {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private AppUser user;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private EventStatus status = EventStatus.ACTIVE;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ScheduleType scheduleType;

	/** EVERY_N_MINUTES only: step between scheduled firings. */
	private Integer intervalMinutes;

	/** DAILY/WEEKLY only: firing time in the user's timezone. */
	private LocalTime timeOfDay;

	/** WEEKLY only: comma-separated ISO day numbers, "1,3,5" = Mon, Wed, Fri. */
	private String daysOfWeek;

	/** First (for ONCE — the only) firing moment. */
	private Instant firstFireAt;

	/** Reminder interval while an occurrence stays open; null = no reminders. */
	private Integer nagIntervalMinutes;

	/** Precomputed next firing moment — the scheduler's polling key; null when FINISHED. */
	private Instant nextFireAt;

	/** When the series moved to FINISHED; null while DRAFT/ACTIVE/PAUSED. */
	private Instant finishedAt;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
}
