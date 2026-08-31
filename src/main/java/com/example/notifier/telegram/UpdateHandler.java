package com.example.notifier.telegram;

import com.example.notifier.domain.AppUser;
import com.example.notifier.domain.ChatState;
import com.example.notifier.domain.Event;
import com.example.notifier.domain.EventStatus;
import com.example.notifier.domain.Occurrence;
import com.example.notifier.domain.OccurrenceStatus;
import com.example.notifier.domain.ScheduleType;
import com.example.notifier.repository.AppUserRepository;
import com.example.notifier.repository.EventRepository;
import com.example.notifier.repository.OccurrenceRepository;
import com.example.notifier.scheduler.ScheduleMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** All bot logic lives here (separate bean so @Transactional works when the library calls consume()). */
@Component
@RequiredArgsConstructor
@Slf4j
public class UpdateHandler {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");
	private static final DateTimeFormatter SHORT_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

	/**
	 * How many message ids /clear scans downward from the command. Telegram won't delete messages
	 * older than 48h anyway, so this only needs to cover a couple of days of chatter; it also bounds
	 * how long the (blocking, single-threaded) delete loop runs. Tunable.
	 */
	private static final int CLEAR_SCAN_LIMIT = 100;

	private final AppUserRepository users;
	private final EventRepository events;
	private final OccurrenceRepository occurrences;
	private final EventWizard wizard;
	private final TelegramSender sender;

	@Transactional
	public void handle(Update update) {
		if (update.hasCallbackQuery()) {
			handleCallback(update.getCallbackQuery());
		} else if (update.hasMessage() && update.getMessage().hasText()) {
			long chatId = update.getMessage().getChatId();
			String text = update.getMessage().getText().trim();
			handleText(chatId, text);
			// Keep the chat tidy: once we've responded, the user's command message is just noise.
			if (text.startsWith("/")) {
				sender.deleteMessage(chatId, update.getMessage().getMessageId());
			}
		}
	}

	private void handleText(long chatId, String text) {
		log.info("Message from chat {}: {}", chatId, text);
		AppUser user = users.findByTelegramChatId(chatId).orElse(null);
		if (text.startsWith("/start")) {
			onStart(chatId, user);
			return;
		}
		if (user == null) {
			sender.send(chatId, "Начнём со /start — надо настроить часовой пояс.");
			return;
		}
		switch (text.split("\\s+")[0]) {
			case "/new" -> {
				wizard.start(user);
				return;
			}
			case "/manage", "/list" -> {
				sendManage(user);
				return;
			}
			case "/schedule" -> {
				sendSchedule(user);
				return;
			}
			case "/clear" -> {
				onClearRequest(chatId);
				return;
			}
			case "/cancel" -> {
				if (EventWizard.isWizardState(user.getChatState())) {
					wizard.cancel(user);
					sender.send(chatId, "✖ Создание события отменено.");
				} else {
					sender.send(chatId, "Сейчас нечего отменять.");
				}
				return;
			}
			case "/help" -> {
				sendHelp(chatId);
				return;
			}
			default -> {
			}
		}
		ChatState state = user.getChatState();
		if (state == ChatState.AWAITING_TIMEZONE) {
			onTimezoneAnswer(chatId, user, text);
		} else if (state != null) {
			wizard.onText(user, text);
		} else {
			sendHelp(chatId);
		}
	}

	private void onStart(long chatId, AppUser existing) {
		AppUser user = existing != null ? existing : new AppUser();
		user.setTelegramChatId(chatId);
		if (user.getTimezone() == null) {
			user.setTimezone("UTC"); // placeholder until the onboarding answer arrives
		}
		user.setChatState(ChatState.AWAITING_TIMEZONE);
		users.save(user);
		sender.send(chatId, """
				Привет! Я буду напоминать тебе о событиях.
				Сначала настроим часовой пояс: напиши, сколько у тебя сейчас времени, в формате ЧЧ:ММ (например, 14:30).""");
	}

	private void onTimezoneAnswer(long chatId, AppUser user, String text) {
		LocalTime userTime;
		try {
			userTime = LocalTime.parse(text, TIME_FORMAT);
		} catch (DateTimeParseException e) {
			sender.send(chatId, "Не понял. Напиши текущее время в формате ЧЧ:ММ, например 14:30.");
			return;
		}
		ZoneOffset offset = TimezoneResolver.resolve(userTime, Instant.now());
		user.setTimezone(offset.getId());
		user.setChatState(null);
		users.save(user);
		sender.send(chatId, """
				Готово! Твой часовой пояс: UTC%s.
				Рабочие часы по умолчанию: %s–%s — вне их я не шлю напоминания.
				Дальше: /new — создать первое событие, /list — список событий.""".formatted(
				offsetLabel(offset), user.getWorkStart(), user.getWorkEnd()));
	}

	/** /manage: one message per event, each with pause/finish/delete buttons. */
	private void sendManage(AppUser user) {
		List<Event> list = activeEvents(user);
		if (list.isEmpty()) {
			sender.send(user.getTelegramChatId(), "Активных событий нет. Создать: /new");
			return;
		}
		ZoneId zone = ZoneId.of(user.getTimezone());
		for (Event event : list) {
			String next = event.getNextFireAt() == null ? "—"
					: event.getNextFireAt().atZone(zone).format(SHORT_FORMAT);
			String status = event.getStatus() == EventStatus.PAUSED ? " (на паузе)" : "";
			sender.send(user.getTelegramChatId(),
					"📌 %s%s\n%s, следующее срабатывание: %s".formatted(
							event.getName(), status, ScheduleMath.describe(event), next),
					Keyboards.listItem(event));
		}
	}

	/** /schedule: a single monospace table of every event, no buttons — just an overview. */
	private void sendSchedule(AppUser user) {
		List<Event> list = activeEvents(user);
		if (list.isEmpty()) {
			sender.send(user.getTelegramChatId(), "Активных событий нет. Создать: /new");
			return;
		}
		ZoneId zone = ZoneId.of(user.getTimezone());
		for (String message : ScheduleTable.render(list, zone)) {
			sender.sendHtml(user.getTelegramChatId(), message);
		}
	}

	private List<Event> activeEvents(AppUser user) {
		return events.findByUserIdAndStatusInOrderByCreatedAt(user.getId(),
				List.of(EventStatus.ACTIVE, EventStatus.PAUSED));
	}

	private void sendHelp(long chatId) {
		sender.send(chatId, """
				/new — создать событие
				/schedule — расписание всех событий таблицей
				/manage — управление событиями (пауза, завершение, удаление)
				/clear — очистить переписку со мной
				/start — перенастроить часовой пояс
				На уведомлениях есть кнопки: ✅ Готово, ⏰ отложить, 🏁 завершить серию.""");
	}

	private void handleCallback(CallbackQuery query) {
		long chatId = query.getMessage().getChatId();
		Integer messageId = query.getMessage().getMessageId();
		log.info("Callback from chat {}: {}", chatId, query.getData());
		AppUser user = users.findByTelegramChatId(chatId).orElse(null);
		if (user == null) {
			sender.answerCallback(query.getId(), "Сначала /start");
			return;
		}
		String[] parts = query.getData().split(":");
		switch (parts[0]) {
			case "sched", "nag", "cal", "new" -> wizard.onCallback(user, query);
			case "done" -> onDone(user, Long.parseLong(parts[1]), query, messageId);
			case "snooze" -> onSnooze(user, Long.parseLong(parts[1]), parts[2], query, messageId);
			case "finish" -> onFinish(user, Long.parseLong(parts[1]), query, messageId);
			case "pause" -> onPause(user, Long.parseLong(parts[1]), query, messageId);
			case "resume" -> onResume(user, Long.parseLong(parts[1]), query, messageId);
			case "delete" -> onDelete(user, Long.parseLong(parts[1]), query, messageId);
			case "clear" -> onClear(user, parts[1], query, messageId);
			default -> sender.answerCallback(query.getId(), "Неизвестная кнопка");
		}
	}

	private void onDone(AppUser user, long occurrenceId, CallbackQuery query, Integer messageId) {
		Occurrence occurrence = occurrences.findById(occurrenceId).orElse(null);
		if (occurrence == null || !owns(user, occurrence.getEvent())
				|| occurrence.getStatus() != OccurrenceStatus.OPEN) {
			sender.answerCallback(query.getId(), "Уже неактуально");
			return;
		}
		Instant now = Instant.now();
		occurrence.setStatus(OccurrenceStatus.DONE);
		occurrence.setDoneAt(now);
		occurrence.setNextReminderAt(null);
		Event event = occurrence.getEvent();
		if (event.getScheduleType() == ScheduleType.ONCE) {
			event.setStatus(EventStatus.FINISHED);
			event.setNextFireAt(null);
		}
		String time = now.atZone(ZoneId.of(user.getTimezone())).format(TIME_FORMAT);
		sender.editMessage(user.getTelegramChatId(), messageId, "✅ " + event.getName() + " — " + time, null);
		sender.answerCallback(query.getId(), "✅ Готово");
	}

	private void onSnooze(AppUser user, long occurrenceId, String amount, CallbackQuery query, Integer messageId) {
		Occurrence occurrence = occurrences.findById(occurrenceId).orElse(null);
		if (occurrence == null || !owns(user, occurrence.getEvent())
				|| occurrence.getStatus() != OccurrenceStatus.OPEN) {
			sender.answerCallback(query.getId(), "Уже неактуально");
			return;
		}
		Instant now = Instant.now();
		ZoneId zone = ZoneId.of(user.getTimezone());
		Instant at = "d".equals(amount)
				? now.atZone(zone).plusDays(1).with(user.getWorkStart()).toInstant()
				: ScheduleMath.deferIntoWorkingHours(now.plus(Long.parseLong(amount), ChronoUnit.MINUTES), user);
		occurrence.setNextReminderAt(at);
		sender.removeButtons(user.getTelegramChatId(), messageId);
		sender.answerCallback(query.getId(), "⏰ Отложено до " + at.atZone(zone).format(SHORT_FORMAT));
	}

	private void onFinish(AppUser user, long eventId, CallbackQuery query, Integer messageId) {
		Event event = events.findById(eventId).orElse(null);
		if (event == null || !owns(user, event) || event.getStatus() == EventStatus.FINISHED) {
			sender.answerCallback(query.getId(), "Уже неактуально");
			return;
		}
		event.setStatus(EventStatus.FINISHED);
		event.setNextFireAt(null);
		// The finish button sits on the open occurrence's own message, which closeOpenOccurrence turns
		// into a trace — so no separate removeButtons(messageId) is needed here.
		closeOpenOccurrence(user, event, "🏁 " + event.getName() + " — серия завершена");
		sender.answerCallback(query.getId(), "🏁 Серия завершена");
	}

	private void onPause(AppUser user, long eventId, CallbackQuery query, Integer messageId) {
		Event event = events.findById(eventId).orElse(null);
		if (event == null || !owns(user, event) || event.getStatus() != EventStatus.ACTIVE) {
			sender.answerCallback(query.getId(), "Уже неактуально");
			return;
		}
		event.setStatus(EventStatus.PAUSED);
		closeOpenOccurrence(user, event, "⏸ " + event.getName() + " — на паузе");
		sender.removeButtons(user.getTelegramChatId(), messageId); // the /list item that carried the pause button
		sender.answerCallback(query.getId(), "⏸ На паузе. Возобновить: /list");
	}

	private void onResume(AppUser user, long eventId, CallbackQuery query, Integer messageId) {
		Event event = events.findById(eventId).orElse(null);
		if (event == null || !owns(user, event) || event.getStatus() != EventStatus.PAUSED) {
			sender.answerCallback(query.getId(), "Уже неактуально");
			return;
		}
		event.setStatus(EventStatus.ACTIVE);
		event.setNextFireAt(ScheduleMath.reactivate(event, Instant.now(), user));
		sender.removeButtons(user.getTelegramChatId(), messageId);
		sender.answerCallback(query.getId(), "▶ Возобновлено");
	}

	private void onDelete(AppUser user, long eventId, CallbackQuery query, Integer messageId) {
		Event event = events.findById(eventId).orElse(null);
		if (event == null || !owns(user, event)) {
			sender.answerCallback(query.getId(), "Уже неактуально");
			return;
		}
		occurrences.deleteByEventId(eventId);
		events.delete(event);
		sender.removeButtons(user.getTelegramChatId(), messageId);
		sender.answerCallback(query.getId(), "🗑 Удалено");
	}

	private void onClearRequest(long chatId) {
		sender.send(chatId, """
				Очистить переписку со мной? Удалю сообщения в этом чате за последние ~48 часов — \
				то, что старше, Telegram боту стирать не даёт.
				События и напоминания не трогаю, только сообщения.""", Keyboards.confirmClear());
	}

	/**
	 * Best-effort chat wipe: Telegram gives bots no "clear history" call, so we walk message ids
	 * downward from the confirmation message and delete each one. Ids are per-chat sequential, so we
	 * never touch other chats; anything older than 48h simply fails and is counted as skipped.
	 */
	private void onClear(AppUser user, String decision, CallbackQuery query, Integer messageId) {
		long chatId = user.getTelegramChatId();
		if (!"yes".equals(decision)) {
			sender.deleteMessage(chatId, messageId); // drop the confirmation prompt itself
			sender.answerCallback(query.getId(), "Отменено");
			return;
		}
		int deleted = 0;
		int floor = Math.max(1, messageId - CLEAR_SCAN_LIMIT);
		for (int id = messageId - 1; id >= floor; id--) {
			if (sender.deleteMessage(chatId, id)) {
				deleted++;
			}
		}
		// Keep the confirmation message as the single survivor and turn it into the summary.
		sender.editMessage(chatId, messageId, ("""
				🧹 Готово: удалил сообщений — %d.
				Что старше 48 часов, Telegram боту стирать не разрешает. Полностью очистить историю \
				можно самому: меню чата → «Очистить историю».""").formatted(deleted), null);
		sender.answerCallback(query.getId(), "Готово");
	}

	/** Close the event's open occurrence and turn its notification message into a compact trace. */
	private void closeOpenOccurrence(AppUser user, Event event, String trace) {
		occurrences.findByEventIdAndStatus(event.getId(), OccurrenceStatus.OPEN).ifPresent(open -> {
			open.setStatus(OccurrenceStatus.SUPERSEDED);
			open.setNextReminderAt(null);
			if (open.getTelegramMessageId() != null) {
				sender.editMessage(user.getTelegramChatId(), open.getTelegramMessageId().intValue(), trace, null);
			}
		});
	}

	private static boolean owns(AppUser user, Event event) {
		return event.getUser().getId().equals(user.getId());
	}

	private static String offsetLabel(ZoneOffset offset) {
		return offset == ZoneOffset.UTC ? "+00:00" : offset.getId();
	}
}
