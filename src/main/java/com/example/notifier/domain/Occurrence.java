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

@Entity
@Table(name = "occurrence")
@Getter
@Setter
@NoArgsConstructor
public class Occurrence {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_id")
	private Event event;

	@Column(nullable = false)
	private Instant firedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OccurrenceStatus status = OccurrenceStatus.OPEN;

	/** When the next reminder is due; snooze also writes here. Null = no reminder planned. */
	private Instant nextReminderAt;

	private Instant doneAt;

	/** Last Telegram message sent for this occurrence — edited when buttons are pressed. */
	private Long telegramMessageId;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
}
