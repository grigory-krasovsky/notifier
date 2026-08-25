package com.example.notifier.telegram;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;

/** Derives a fixed UTC offset from the "what time is it for you now" onboarding answer. */
public final class TimezoneResolver {

	private TimezoneResolver() {
	}

	/**
	 * Difference between the user's wall clock and UTC, wrapped across midnight
	 * and rounded to 15 minutes to absorb clock skew.
	 */
	public static ZoneOffset resolve(LocalTime userNow, Instant serverNow) {
		LocalTime utcNow = LocalTime.ofInstant(serverNow, ZoneOffset.UTC);
		int diffMinutes = userNow.toSecondOfDay() / 60 - utcNow.toSecondOfDay() / 60;
		int wrapped = Math.floorMod(diffMinutes + 720, 1440) - 720;
		int rounded = Math.round(wrapped / 15f) * 15;
		return ZoneOffset.ofTotalSeconds(rounded * 60);
	}
}
