package com.example.notifier.telegram;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TimezoneResolverTest {

	private static final Instant NOON_UTC = Instant.parse("2026-08-25T12:00:00Z");

	@Test
	void plainOffset() {
		assertThat(TimezoneResolver.resolve(LocalTime.of(15, 0), NOON_UTC)).isEqualTo(ZoneOffset.ofHours(3));
	}

	@Test
	void roundsClockSkewToQuarterHour() {
		assertThat(TimezoneResolver.resolve(LocalTime.of(15, 4), NOON_UTC)).isEqualTo(ZoneOffset.ofHours(3));
	}

	@Test
	void wrapsAcrossMidnightForward() {
		assertThat(TimezoneResolver.resolve(LocalTime.of(1, 0), Instant.parse("2026-08-25T23:00:00Z")))
				.isEqualTo(ZoneOffset.ofHours(2));
	}

	@Test
	void wrapsAcrossMidnightBackward() {
		assertThat(TimezoneResolver.resolve(LocalTime.of(23, 0), Instant.parse("2026-08-26T01:00:00Z")))
				.isEqualTo(ZoneOffset.ofHours(-2));
	}

	@Test
	void supportsHalfHourZones() {
		assertThat(TimezoneResolver.resolve(LocalTime.of(17, 30), NOON_UTC))
				.isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
	}
}
