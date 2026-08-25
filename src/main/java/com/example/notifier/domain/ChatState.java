package com.example.notifier.domain;

/** Multi-step dialog state persisted per user so conversations survive restarts. */
public enum ChatState {
	AWAITING_TIMEZONE,
	NEW_AWAITING_NAME,
	NEW_AWAITING_FIRST_FIRE,
	NEW_AWAITING_SCHEDULE,
	NEW_AWAITING_INTERVAL_MINUTES,
	NEW_AWAITING_INTERVAL_HOURS,
	NEW_AWAITING_NAG
}
