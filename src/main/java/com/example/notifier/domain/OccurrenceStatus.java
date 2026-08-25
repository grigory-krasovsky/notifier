package com.example.notifier.domain;

public enum OccurrenceStatus {
	OPEN,
	DONE,
	/** Closed automatically because a newer occurrence of the same event fired. */
	SUPERSEDED
}
