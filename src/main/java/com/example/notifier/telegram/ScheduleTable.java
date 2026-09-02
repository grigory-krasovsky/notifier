package com.example.notifier.telegram;

import com.example.notifier.domain.Event;
import com.example.notifier.domain.EventStatus;
import com.example.notifier.scheduler.ScheduleMath;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Renders the /schedule overview as a monospace HTML table (Telegram has no real tables,
 * but a {@code <pre>} block keeps columns aligned). Long lists are split across messages
 * to stay under Telegram's 4096-char limit.
 */
public final class ScheduleTable {

	private static final DateTimeFormatter NEXT = DateTimeFormatter.ofPattern("dd.MM HH:mm");
	private static final int NAME_W = 24;
	private static final int MAX_ROWS = 40; // keeps each message well under Telegram's 4096-char limit

	private ScheduleTable() {
	}

	/**
	 * /schedule overview: one or more ready-to-send HTML messages; empty list for no events.
	 * {@code nextReminderByEvent} maps an event id to the pending reminder of its open occurrence
	 * (if any), so the "next" column shows the soonest ping — a firing or a reminder alike.
	 */
	public static List<String> render(List<Event> events, ZoneId zone, Map<Long, Instant> nextReminderByEvent) {
		return paginate(events, "Следующее", event -> nextCell(event, zone, nextReminderByEvent));
	}

	/** /finished overview: same table shape, but the right column is the completion date. */
	public static List<String> renderFinished(List<Event> events, ZoneId zone) {
		return paginate(events, "Завершено", event -> finishedCell(event, zone));
	}

	private static List<String> paginate(List<Event> events, String rightHeader, Function<Event, String> rightCell) {
		List<String> messages = new ArrayList<>();
		for (int start = 0; start < events.size(); start += MAX_ROWS) {
			List<Event> page = events.subList(start, Math.min(start + MAX_ROWS, events.size()));
			messages.add(renderPage(page, rightHeader, rightCell));
		}
		return messages;
	}

	private static String renderPage(List<Event> events, String rightHeader, Function<Event, String> rightCell) {
		StringBuilder sb = new StringBuilder("<pre>");
		sb.append(row("Событие", rightHeader));
		for (Event event : events) {
			sb.append(row(event.getName(), rightCell.apply(event)));
		}
		return sb.append("</pre>").toString();
	}

	private static String nextCell(Event event, ZoneId zone, Map<Long, Instant> nextReminderByEvent) {
		if (event.getStatus() == EventStatus.PAUSED) {
			return "пауза";
		}
		Instant next = ScheduleMath.nextPing(event.getNextFireAt(), nextReminderByEvent.get(event.getId()));
		return next == null ? "—" : next.atZone(zone).format(NEXT);
	}

	private static String finishedCell(Event event, ZoneId zone) {
		return event.getFinishedAt() == null ? "—" : event.getFinishedAt().atZone(zone).format(NEXT);
	}

	private static String row(String name, String next) {
		return pad(name, NAME_W) + "  " + esc(next) + "\n";
	}

	/** Truncate to the visible width, then escape — padding stays correct because entities render as one char. */
	private static String pad(String value, int width) {
		String cell = trunc(value, width);
		int padding = width - cell.length();
		return esc(cell) + (padding > 0 ? " ".repeat(padding) : "");
	}

	private static String trunc(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max - 1) + "…";
	}

	private static String esc(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
