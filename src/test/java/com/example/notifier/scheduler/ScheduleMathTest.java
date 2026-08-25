package com.example.notifier.scheduler;

import com.example.notifier.domain.AppUser;
import com.example.notifier.domain.Event;
import com.example.notifier.domain.ScheduleType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleMathTest {

	private final AppUser user = userWithZone("+03:00"); // work hours default 09:00-22:00

	@Test
	void insideWorkingHoursUnchanged() {
		Instant candidate = Instant.parse("2026-08-25T12:00:00Z"); // 15:00 local
		assertThat(ScheduleMath.deferIntoWorkingHours(candidate, user)).isEqualTo(candidate);
	}

	@Test
	void nightDefersToSameMorning() {
		Instant candidate = Instant.parse("2026-08-25T00:30:00Z"); // 03:30 local
		assertThat(ScheduleMath.deferIntoWorkingHours(candidate, user))
				.isEqualTo(Instant.parse("2026-08-25T06:00:00Z")); // 09:00 local
	}

	@Test
	void lateEveningDefersToNextMorning() {
		Instant candidate = Instant.parse("2026-08-25T20:00:00Z"); // 23:00 local
		assertThat(ScheduleMath.deferIntoWorkingHours(candidate, user))
				.isEqualTo(Instant.parse("2026-08-26T06:00:00Z"));
	}

	@Test
	void dailyMovesToNextDayWhenTimePassed() {
		Event event = new Event();
		event.setScheduleType(ScheduleType.DAILY);
		event.setTimeOfDay(LocalTime.of(9, 0));
		Instant after = Instant.parse("2026-08-25T07:00:00Z"); // 10:00 local, past 09:00
		assertThat(ScheduleMath.nextFireAfter(event, after, user))
				.isEqualTo(Instant.parse("2026-08-26T06:00:00Z"));
	}

	@Test
	void intervalFiringLandingAtNightIsDeferredToMorning() {
		Event event = new Event();
		event.setScheduleType(ScheduleType.EVERY_N_MINUTES);
		event.setIntervalMinutes(120);
		Instant after = Instant.parse("2026-08-25T19:30:00Z"); // 22:30 local
		assertThat(ScheduleMath.nextFireAfter(event, after, user))
				.isEqualTo(Instant.parse("2026-08-26T06:00:00Z")); // 00:30 local -> next 09:00
	}

	@Test
	void onceHasNoNextFire() {
		Event event = new Event();
		event.setScheduleType(ScheduleType.ONCE);
		assertThat(ScheduleMath.nextFireAfter(event, Instant.parse("2026-08-25T12:00:00Z"), user)).isNull();
	}

	private static AppUser userWithZone(String zone) {
		AppUser user = new AppUser();
		user.setTimezone(zone);
		return user;
	}
}
