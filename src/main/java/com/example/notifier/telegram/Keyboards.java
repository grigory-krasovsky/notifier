package com.example.notifier.telegram;

import com.example.notifier.domain.Event;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public final class Keyboards {

	private static final String[] RU_MONTHS = {
			"Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
			"Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
	};
	private static final String[] RU_WEEKDAYS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
	private static final String NOOP = "cal:x"; // inert cells (labels, blanks, past days)

	private Keyboards() {
	}

	public static InlineKeyboardMarkup schedulePresets() {
		return InlineKeyboardMarkup.builder()
				.keyboardRow(new InlineKeyboardRow(
						btn("Однократно", "sched:ONCE"), btn("Каждый день", "sched:DAILY")))
				.keyboardRow(new InlineKeyboardRow(
						btn("Каждые N минут", "sched:EVERY_MIN"), btn("Каждые N часов", "sched:EVERY_HOUR")))
				.keyboardRow(cancelRow())
				.build();
	}

	public static InlineKeyboardMarkup nagPresets() {
		return InlineKeyboardMarkup.builder()
				.keyboardRow(new InlineKeyboardRow(btn("Без напоминаний", "nag:0")))
				.keyboardRow(new InlineKeyboardRow(
						btn("10 мин", "nag:10"), btn("30 мин", "nag:30"),
						btn("1 час", "nag:60"), btn("3 часа", "nag:180")))
				.keyboardRow(cancelRow())
				.build();
	}

	/** Buttons under a firing/reminder message; finish button only for recurring events. */
	public static InlineKeyboardMarkup notification(long occurrenceId, Long finishableEventId) {
		InlineKeyboardRow actions = new InlineKeyboardRow(
				btn("✅ Готово", "done:" + occurrenceId),
				btn("⏰ 10 мин", "snooze:" + occurrenceId + ":10"),
				btn("⏰ 1 час", "snooze:" + occurrenceId + ":60"),
				btn("⏰ Завтра", "snooze:" + occurrenceId + ":d"));
		InlineKeyboardMarkup.InlineKeyboardMarkupBuilder builder = InlineKeyboardMarkup.builder().keyboardRow(actions);
		if (finishableEventId != null) {
			builder.keyboardRow(new InlineKeyboardRow(btn("🏁 Завершить серию", "finish:" + finishableEventId)));
		}
		return builder.build();
	}

	/** Yes/No confirmation for the destructive /clear command. */
	public static InlineKeyboardMarkup confirmClear() {
		return InlineKeyboardMarkup.builder()
				.keyboardRow(new InlineKeyboardRow(
						btn("🧹 Да, очистить", "clear:yes"), btn("Отмена", "clear:no")))
				.build();
	}

	/** Buttons under each /manage item: pause and its inverse (continue), edit, delete. */
	public static InlineKeyboardMarkup listItem(Event event) {
		long id = event.getId();
		return InlineKeyboardMarkup.builder()
				.keyboardRow(new InlineKeyboardRow(
						btn("⏸ Пауза", "pause:" + id), btn("▶ Продолжить", "resume:" + id)))
				.keyboardRow(new InlineKeyboardRow(
						btn("✏️ Изменить", "edit:" + id), btn("🗑 Удалить", "delete:" + id)))
				.build();
	}

	/** Field picker shown when the user taps "✏️ Изменить" on a /manage item. */
	public static InlineKeyboardMarkup editMenu(long eventId) {
		return InlineKeyboardMarkup.builder()
				.keyboardRow(new InlineKeyboardRow(
						btn("✏️ Название", "edit:" + eventId + ":name"),
						btn("🔔 Напоминания", "edit:" + eventId + ":nag")))
				.keyboardRow(new InlineKeyboardRow(
						btn("🔁 Периодичность", "edit:" + eventId + ":sched"),
						btn("🕒 Время", "edit:" + eventId + ":time")))
				.keyboardRow(new InlineKeyboardRow(btn("✖ Отмена", "edit:" + eventId + ":cancel")))
				.build();
	}

	/** Schedule presets for editing (mirror of {@link #schedulePresets()} but carrying the event id). */
	public static InlineKeyboardMarkup editSchedulePresets(long eventId) {
		return InlineKeyboardMarkup.builder()
				.keyboardRow(new InlineKeyboardRow(
						btn("Однократно", "esched:" + eventId + ":ONCE"),
						btn("Каждый день", "esched:" + eventId + ":DAILY")))
				.keyboardRow(new InlineKeyboardRow(
						btn("Каждые N минут", "esched:" + eventId + ":EVERY_MIN"),
						btn("Каждые N часов", "esched:" + eventId + ":EVERY_HOUR")))
				.keyboardRow(new InlineKeyboardRow(btn("✖ Отмена", "edit:" + eventId + ":cancel")))
				.build();
	}

	/** Reminder presets for editing (mirror of {@link #nagPresets()} but carrying the event id). */
	public static InlineKeyboardMarkup editNagPresets(long eventId) {
		return InlineKeyboardMarkup.builder()
				.keyboardRow(new InlineKeyboardRow(btn("Без напоминаний", "enag:" + eventId + ":0")))
				.keyboardRow(new InlineKeyboardRow(
						btn("10 мин", "enag:" + eventId + ":10"), btn("30 мин", "enag:" + eventId + ":30"),
						btn("1 час", "enag:" + eventId + ":60"), btn("3 часа", "enag:" + eventId + ":180")))
				.keyboardRow(new InlineKeyboardRow(btn("✖ Отмена", "edit:" + eventId + ":cancel")))
				.build();
	}

	/**
	 * Month grid, week starts Monday. Navigation month and picked day are encoded in the
	 * callback data (stateless): {@code cal:nav:YYYY-MM}, {@code cal:day:YYYY-MM-DD}.
	 * Days before {@code today} are shown inert; the back arrow is hidden in the current month.
	 */
	public static InlineKeyboardMarkup calendar(YearMonth month, LocalDate today) {
		List<InlineKeyboardRow> rows = new ArrayList<>();
		YearMonth current = YearMonth.from(today);

		InlineKeyboardRow header = new InlineKeyboardRow();
		header.add(month.isAfter(current) ? btn("‹", "cal:nav:" + month.minusMonths(1)) : btn("·", NOOP));
		header.add(btn(RU_MONTHS[month.getMonthValue() - 1] + " " + month.getYear(), NOOP));
		header.add(btn("›", "cal:nav:" + month.plusMonths(1)));
		rows.add(header);

		InlineKeyboardRow weekdays = new InlineKeyboardRow();
		for (String label : RU_WEEKDAYS) {
			weekdays.add(btn(label, NOOP));
		}
		rows.add(weekdays);

		int lead = month.atDay(1).getDayOfWeek().getValue() - 1; // Monday=1 -> 0 blanks
		InlineKeyboardRow row = new InlineKeyboardRow();
		for (int i = 0; i < lead; i++) {
			row.add(btn("·", NOOP));
		}
		for (int day = 1; day <= month.lengthOfMonth(); day++) {
			LocalDate date = month.atDay(day);
			if (date.isBefore(today)) {
				row.add(btn("·", NOOP)); // past: inert
			} else {
				String label = date.equals(today) ? "[" + day + "]" : String.valueOf(day); // mark today
				row.add(btn(label, "cal:day:" + date));
			}
			if (row.size() == 7) {
				rows.add(row);
				row = new InlineKeyboardRow();
			}
		}
		if (!row.isEmpty()) {
			while (row.size() < 7) {
				row.add(btn("·", NOOP));
			}
			rows.add(row);
		}
		rows.add(cancelRow());

		InlineKeyboardMarkup.InlineKeyboardMarkupBuilder builder = InlineKeyboardMarkup.builder();
		rows.forEach(builder::keyboardRow);
		return builder.build();
	}

	/** Hours 00–23, six per row; each button carries the already-chosen date. */
	public static InlineKeyboardMarkup hourGrid(LocalDate date) {
		InlineKeyboardMarkup.InlineKeyboardMarkupBuilder builder = InlineKeyboardMarkup.builder();
		InlineKeyboardRow row = new InlineKeyboardRow();
		for (int hour = 0; hour < 24; hour++) {
			String hh = String.format("%02d", hour);
			row.add(btn(hh, "cal:hour:" + date + ":" + hh));
			if (row.size() == 6) {
				builder.keyboardRow(row);
				row = new InlineKeyboardRow();
			}
		}
		builder.keyboardRow(cancelRow());
		return builder.build();
	}

	/** Minutes in 5-minute steps for the chosen date and hour. */
	public static InlineKeyboardMarkup minuteGrid(LocalDate date, int hour) {
		String hh = String.format("%02d", hour);
		InlineKeyboardMarkup.InlineKeyboardMarkupBuilder builder = InlineKeyboardMarkup.builder();
		InlineKeyboardRow row = new InlineKeyboardRow();
		for (int minute = 0; minute < 60; minute += 5) {
			String mm = String.format("%02d", minute);
			row.add(btn(hh + ":" + mm, "cal:min:" + date + ":" + hh + ":" + mm));
			if (row.size() == 4) {
				builder.keyboardRow(row);
				row = new InlineKeyboardRow();
			}
		}
		if (!row.isEmpty()) {
			builder.keyboardRow(row);
		}
		builder.keyboardRow(cancelRow());
		return builder.build();
	}

	/** Cancel row shared by every /new wizard keyboard; aborts the draft. */
	private static InlineKeyboardRow cancelRow() {
		return new InlineKeyboardRow(btn("✖ Отмена", "new:cancel"));
	}

	private static InlineKeyboardButton btn(String text, String callbackData) {
		return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
	}
}
