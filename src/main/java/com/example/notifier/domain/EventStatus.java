package com.example.notifier.domain;

public enum EventStatus {
	/** Being assembled by the /new wizard; invisible to the scheduler and /list. */
	DRAFT,
	ACTIVE,
	PAUSED,
	FINISHED
}
