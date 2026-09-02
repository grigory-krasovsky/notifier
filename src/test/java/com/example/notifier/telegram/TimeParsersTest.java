package com.example.notifier.telegram;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class TimeParsersTest {

	private static final ZoneId ZONE = ZoneId.of("+03:00");
	private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z"); // 15:00 local

	@Test
	void bareTimeAheadMeansToday() {
		assertThat(TimeParsers.parseFirstFire("18:30", ZONE, NOW))
				.contains(Instant.parse("2026-08-25T15:30:00Z"));
	}

	@Test
	void bareTimePassedMeansTomorrow() {
		assertThat(TimeParsers.parseFirstFire("9:00", ZONE, NOW))
				.contains(Instant.parse("2026-08-26T06:00:00Z"));
	}

	@Test
	void explicitDateWithoutYear() {
		assertThat(TimeParsers.parseFirstFire("31.12 23:30", ZONE, NOW))
				.contains(Instant.parse("2026-12-31T20:30:00Z"));
	}

	@Test
	void explicitDateWithYear() {
		assertThat(TimeParsers.parseFirstFire("01.01.2027 10:00", ZONE, NOW))
				.contains(Instant.parse("2027-01-01T07:00:00Z"));
	}

	@Test
	void rejectsGarbage() {
		assertThat(TimeParsers.parseFirstFire("завтра", ZONE, NOW)).isEmpty();
		assertThat(TimeParsers.parseFirstFire("25:70", ZONE, NOW)).isEmpty();
		assertThat(TimeParsers.parseFirstFire("32.13 10:00", ZONE, NOW)).isEmpty();
	}

	@Test
	void parsesTimeOfDay() {
		assertThat(TimeParsers.parseTimeOfDay("9:05")).contains(LocalTime.of(9, 5));
		assertThat(TimeParsers.parseTimeOfDay(" 23:59 ")).contains(LocalTime.of(23, 59));
	}

	@Test
	void rejectsBadTimeOfDay() {
		assertThat(TimeParsers.parseTimeOfDay("24:00")).isEmpty();
		assertThat(TimeParsers.parseTimeOfDay("9:60")).isEmpty();
		assertThat(TimeParsers.parseTimeOfDay("25.12 10:00")).isEmpty();
		assertThat(TimeParsers.parseTimeOfDay("вечером")).isEmpty();
	}
}
