package com.example.notifier.scheduler;

import com.example.notifier.domain.AppUser;
import com.example.notifier.domain.Event;
import com.example.notifier.domain.ScheduleType;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

public final class ScheduleMath {

	private ScheduleMath() {
	}

	/**
	 * Reminders and interval firings are held until the user's working hours;
	 * explicit-time schedules (DAILY/ONCE at a chosen time) bypass this on purpose.
	 */
	public static Instant deferIntoWorkingHours(Instant candidate, AppUser user) {
		ZoneId zone = ZoneId.of(user.getTimezone());
		ZonedDateTime local = candidate.atZone(zone);
		LocalTime time = local.toLocalTime();
		if (!time.isBefore(user.getWorkStart()) && time.isBefore(user.getWorkEnd())) {
			return candidate;
		}
		ZonedDateTime deferred = time.isBefore(user.getWorkStart())
				? local.with(user.getWorkStart())
				: local.plusDays(1).with(user.getWorkStart());
		return deferred.toInstant();
	}

	/** Next scheduled firing strictly after {@code after}; null when the series is over. */
	public static Instant nextFireAfter(Event event, Instant after, AppUser user) {
		ZoneId zone = ZoneId.of(user.getTimezone());
		return switch (event.getScheduleType()) {
			case ONCE, WEEKLY -> null; // WEEKLY is not offered by the wizard yet
			case EVERY_N_MINUTES ->
					deferIntoWorkingHours(after.plus(event.getIntervalMinutes(), ChronoUnit.MINUTES), user);
			case DAILY -> {
				ZonedDateTime candidate = after.atZone(zone).with(event.getTimeOfDay()).withSecond(0).withNano(0);
				if (!candidate.toInstant().isAfter(after)) {
					candidate = candidate.plusDays(1);
				}
				yield candidate.toInstant();
			}
		};
	}

	/** Where next_fire_at should point when a paused or overdue event comes back to life. */
	public static Instant reactivate(Event event, Instant now, AppUser user) {
		if (event.getScheduleType() == ScheduleType.ONCE) {
			return event.getFirstFireAt() != null && event.getFirstFireAt().isAfter(now)
					? event.getFirstFireAt() : now;
		}
		if (event.getNextFireAt() != null && event.getNextFireAt().isAfter(now)) {
			return event.getNextFireAt();
		}
		return nextFireAfter(event, now, user);
	}

	/**
	 * The next moment the bot will ping the user for this event: the earliest of its next scheduled
	 * firing ({@code nextFireAt}) and a pending reminder on an open occurrence ({@code nextReminderAt}).
	 * Either may be null (a fired one-shot has no next firing; an event with no open occurrence has no
	 * reminder); null only when both are.
	 */
	public static Instant nextPing(Instant nextFireAt, Instant nextReminderAt) {
		if (nextFireAt == null) {
			return nextReminderAt;
		}
		if (nextReminderAt == null) {
			return nextFireAt;
		}
		return nextFireAt.isBefore(nextReminderAt) ? nextFireAt : nextReminderAt;
	}

	public static String describe(Event event) {
		return switch (event.getScheduleType()) {
			case ONCE -> "однократно";
			case EVERY_N_MINUTES -> {
				int minutes = event.getIntervalMinutes();
				yield minutes % 60 == 0 ? "каждые " + minutes / 60 + " ч" : "каждые " + minutes + " мин";
			}
			case DAILY -> "ежедневно в " + event.getTimeOfDay();
			case WEEKLY -> "еженедельно";
		};
	}
}
