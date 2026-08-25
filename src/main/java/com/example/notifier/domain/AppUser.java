package com.example.notifier.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalTime;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private Long telegramChatId;

	/** IANA zone id, e.g. "Europe/Moscow". */
	@Column(nullable = false)
	private String timezone;

	/** Working hours (user-local): reminders and interval firings are held outside them. */
	@Column(nullable = false)
	private LocalTime workStart = LocalTime.of(9, 0);

	@Column(nullable = false)
	private LocalTime workEnd = LocalTime.of(22, 0);

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
}
