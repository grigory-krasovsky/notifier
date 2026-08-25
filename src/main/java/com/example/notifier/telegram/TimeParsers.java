package com.example.notifier.telegram;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeParsers {

	private static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
	private static final Pattern DATE_TIME = Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?\\s+(\\d{1,2}):(\\d{2})$");

	private TimeParsers() {
	}

	/** "18:30" = today in the user's zone (tomorrow if already past); "25.12 14:00"; "25.12.2027 14:00". */
	public static Optional<Instant> parseFirstFire(String text, ZoneId zone, Instant now) {
		String trimmed = text.trim();
		ZonedDateTime nowLocal = now.atZone(zone);

		Matcher m = TIME.matcher(trimmed);
		if (m.matches()) {
			LocalTime time = toTime(m.group(1), m.group(2));
			if (time == null) {
				return Optional.empty();
			}
			ZonedDateTime candidate = nowLocal.with(time).withSecond(0).withNano(0);
			if (!candidate.isAfter(nowLocal)) {
				candidate = candidate.plusDays(1);
			}
			return Optional.of(candidate.toInstant());
		}

		m = DATE_TIME.matcher(trimmed);
		if (m.matches()) {
			LocalTime time = toTime(m.group(4), m.group(5));
			if (time == null) {
				return Optional.empty();
			}
			try {
				int year = m.group(3) != null ? Integer.parseInt(m.group(3)) : nowLocal.getYear();
				LocalDate date = LocalDate.of(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
				ZonedDateTime candidate = ZonedDateTime.of(date, time, zone);
				if (m.group(3) == null && !candidate.isAfter(nowLocal)) {
					candidate = candidate.plusYears(1);
				}
				return Optional.of(candidate.toInstant());
			} catch (DateTimeException e) {
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	private static LocalTime toTime(String hour, String minute) {
		int h = Integer.parseInt(hour);
		int m = Integer.parseInt(minute);
		return h > 23 || m > 59 ? null : LocalTime.of(h, m);
	}
}
