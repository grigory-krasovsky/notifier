package com.example.notifier.telegram;

import com.example.notifier.domain.Event;
import com.example.notifier.domain.EventStatus;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

public final class Keyboards {

	private Keyboards() {
	}

	public static InlineKeyboardMarkup schedulePresets() {
		return InlineKeyboardMarkup.builder()
				.keyboardRow(new InlineKeyboardRow(
						btn("Однократно", "sched:ONCE"), btn("Каждый день", "sched:DAILY")))
				.keyboardRow(new InlineKeyboardRow(
						btn("Каждые N минут", "sched:EVERY_MIN"), btn("Каждые N часов", "sched:EVERY_HOUR")))
				.build();
	}

	public static InlineKeyboardMarkup nagPresets() {
		return InlineKeyboardMarkup.builder()
				.keyboardRow(new InlineKeyboardRow(btn("Без напоминаний", "nag:0")))
				.keyboardRow(new InlineKeyboardRow(
						btn("10 мин", "nag:10"), btn("30 мин", "nag:30"),
						btn("1 час", "nag:60"), btn("3 часа", "nag:180")))
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

	public static InlineKeyboardMarkup listItem(Event event) {
		InlineKeyboardButton toggle = event.getStatus() == EventStatus.PAUSED
				? btn("▶ Возобновить", "resume:" + event.getId())
				: btn("⏸ Пауза", "pause:" + event.getId());
		return InlineKeyboardMarkup.builder()
				.keyboardRow(new InlineKeyboardRow(
						toggle, btn("🏁 Завершить", "finish:" + event.getId()), btn("🗑 Удалить", "delete:" + event.getId())))
				.build();
	}

	private static InlineKeyboardButton btn(String text, String callbackData) {
		return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
	}
}
