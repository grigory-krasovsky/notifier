package com.example.notifier.telegram;

import com.example.notifier.domain.Event;
import com.example.notifier.domain.EventStatus;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

	/** One or more ready-to-send HTML messages; empty list for no events. */
	public static List<String> render(List<Event> events, ZoneId zone) {
		List<String> messages = new ArrayList<>();
		for (int start = 0; start < events.size(); start += MAX_ROWS) {
			List<Event> page = events.subList(start, Math.min(start + MAX_ROWS, events.size()));
			messages.add(renderPage(page, zone));
		}
		return messages;
	}

	private static String renderPage(List<Event> events, ZoneId zone) {
		StringBuilder sb = new StringBuilder("<pre>");
		sb.append(row("Событие", "Следующее"));
		for (Event event : events) {
			String next = event.getStatus() == EventStatus.PAUSED ? "пауза"
					: event.getNextFireAt() == null ? "—" : event.getNextFireAt().atZone(zone).format(NEXT);
			sb.append(row(event.getName(), next));
		}
		return sb.append("</pre>").toString();
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
