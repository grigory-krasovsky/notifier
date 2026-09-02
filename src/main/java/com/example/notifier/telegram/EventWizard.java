package com.example.notifier.telegram;

import com.example.notifier.domain.AppUser;
import com.example.notifier.domain.ChatState;
import com.example.notifier.domain.Event;
import com.example.notifier.domain.EventStatus;
import com.example.notifier.domain.ScheduleType;
import com.example.notifier.repository.AppUserRepository;
import com.example.notifier.repository.EventRepository;
import com.example.notifier.repository.OccurrenceRepository;
import com.example.notifier.scheduler.ScheduleMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Two dialogs over events:
 * <ul>
 *   <li>/new — name → first firing → schedule preset → reminder preset → active event;</li>
 *   <li>editing an existing event from /manage — one field at a time (name, schedule, time, reminders).</li>
 * </ul>
 * Both share the calendar, schedule/reminder presets and the interval prompts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventWizard {

	private static final DateTimeFormatter SUMMARY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
	private static final DateTimeFormatter NEXT_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

	private final AppUserRepository users;
	private final EventRepository events;
	private final OccurrenceRepository occurrences;
	private final TelegramSender sender;

	public void start(AppUser user) {
		events.findFirstByUserIdAndStatus(user.getId(), EventStatus.DRAFT).ifPresent(stale -> {
			occurrences.deleteByEventId(stale.getId());
			events.delete(stale);
		});
		Event draft = new Event();
		draft.setUser(user);
		draft.setName("");
		draft.setStatus(EventStatus.DRAFT);
		draft.setScheduleType(ScheduleType.ONCE);
		events.save(draft);
		setState(user, ChatState.NEW_AWAITING_NAME);
		sender.send(user.getTelegramChatId(), "Как назовём событие? (/cancel — отмена)");
	}

	public void onText(AppUser user, String text) {
		if (isEditState(user.getChatState())) {
			onEditText(user, text);
			return;
		}
		long chatId = user.getTelegramChatId();
		Event draft = draft(user);
		if (draft == null) {
			setState(user, null);
			sender.send(chatId, "Черновик потерялся — начни заново: /new");
			return;
		}
		switch (user.getChatState()) {
			case NEW_AWAITING_NAME -> {
				if (text.isBlank() || text.length() > 200) {
					sender.send(chatId, "Название должно быть от 1 до 200 символов. Попробуй ещё раз.");
					return;
				}
				draft.setName(text);
				events.save(draft);
				setState(user, ChatState.NEW_AWAITING_FIRST_FIRE);
				sendCalendar(user);
			}
			case NEW_AWAITING_FIRST_FIRE -> {
				Optional<Instant> parsed = TimeParsers.parseFirstFire(text, zone(user), Instant.now());
				if (parsed.isEmpty()) {
					sender.send(chatId, "Не понял. Примеры: 18:30 или 25.12 14:00");
					return;
				}
				draft.setFirstFireAt(parsed.get());
				draft.setNextFireAt(parsed.get());
				events.save(draft);
				setState(user, ChatState.NEW_AWAITING_SCHEDULE);
				sender.send(chatId, "Периодичность события?", Keyboards.schedulePresets());
			}
			case NEW_AWAITING_INTERVAL_MINUTES, NEW_AWAITING_INTERVAL_HOURS -> {
				Integer value = parsePositiveInt(text);
				if (value == null || value > 10_000) {
					sender.send(chatId, "Напиши целое число, например 45.");
					return;
				}
				draft.setIntervalMinutes(
						user.getChatState() == ChatState.NEW_AWAITING_INTERVAL_HOURS ? value * 60 : value);
				events.save(draft);
				askNag(user);
			}
			default -> sender.send(chatId, "Выбери вариант кнопками выше 👆");
		}
	}

	public void onCallback(AppUser user, CallbackQuery query) {
		long chatId = user.getTelegramChatId();
		String[] parts = query.getData().split(":");
		if ("new".equals(parts[0]) && "cancel".equals(parts[1])) {
			if (!isWizardState(user.getChatState())) {
				sender.answerCallback(query.getId(), "Уже неактуально");
				return;
			}
			cancel(user);
			sender.answerCallback(query.getId(), "Отменено");
			sender.editMessage(chatId, query.getMessage().getMessageId(), "✖ Создание события отменено", null);
			return;
		}
		switch (parts[0]) {
			case "edit", "esched", "enag" -> {
				onEditCallback(user, query, parts);
				return;
			}
			default -> {
			}
		}
		if ("cal".equals(parts[0]) && isEditState(user.getChatState())) {
			onEditCalendar(user, parts, query);
			return;
		}
		Event draft = draft(user);
		if (draft == null) {
			sender.answerCallback(query.getId(), "Черновика нет — начни с /new");
			return;
		}
		if ("cal".equals(parts[0])) {
			onCalendar(user, draft, parts, query);
			return;
		}
		if ("sched".equals(parts[0]) && user.getChatState() == ChatState.NEW_AWAITING_SCHEDULE) {
			sender.answerCallback(query.getId(), null);
			switch (parts[1]) {
				case "ONCE" -> {
					draft.setScheduleType(ScheduleType.ONCE);
					events.save(draft);
					askNag(user);
				}
				case "DAILY" -> {
					draft.setScheduleType(ScheduleType.DAILY);
					draft.setTimeOfDay(draft.getFirstFireAt().atZone(zone(user)).toLocalTime());
					events.save(draft);
					askNag(user);
				}
				case "EVERY_MIN" -> {
					draft.setScheduleType(ScheduleType.EVERY_N_MINUTES);
					events.save(draft);
					setState(user, ChatState.NEW_AWAITING_INTERVAL_MINUTES);
					sender.send(chatId, "Каждые сколько минут? Напиши число. (/cancel — отмена)");
				}
				case "EVERY_HOUR" -> {
					draft.setScheduleType(ScheduleType.EVERY_N_MINUTES);
					events.save(draft);
					setState(user, ChatState.NEW_AWAITING_INTERVAL_HOURS);
					sender.send(chatId, "Каждые сколько часов? Напиши число. (/cancel — отмена)");
				}
				default -> log.warn("Unknown schedule preset: {}", query.getData());
			}
			return;
		}
		if ("nag".equals(parts[0]) && user.getChatState() == ChatState.NEW_AWAITING_NAG) {
			int minutes = Integer.parseInt(parts[1]);
			draft.setNagIntervalMinutes(minutes == 0 ? null : minutes);
			draft.setStatus(EventStatus.ACTIVE);
			events.save(draft);
			setState(user, null);
			sender.answerCallback(query.getId(), null);
			sender.send(chatId, summary(user, draft));
			return;
		}
		sender.answerCallback(query.getId(), "Эта кнопка уже неактуальна");
	}

	private void sendCalendar(AppUser user) {
		LocalDate today = LocalDate.now(zone(user));
		sender.send(user.getTelegramChatId(),
				"Когда первое срабатывание? Выбери дату — или напиши вручную: ЧЧ:ММ либо ДД.ММ ЧЧ:ММ.",
				Keyboards.calendar(YearMonth.from(today), today));
	}

	private void onCalendar(AppUser user, Event draft, String[] parts, CallbackQuery query) {
		long chatId = user.getTelegramChatId();
		int messageId = query.getMessage().getMessageId();
		if (user.getChatState() != ChatState.NEW_AWAITING_FIRST_FIRE) {
			sender.answerCallback(query.getId(), "Эта кнопка уже неактуальна");
			return;
		}
		ZoneId zone = zone(user);
		LocalDate today = LocalDate.now(zone);
		sender.answerCallback(query.getId(), null);
		Optional<Instant> picked = calendarStep(user, parts, messageId);
		if (picked.isEmpty()) {
			return;
		}
		Instant chosen = picked.get();
		if (!chosen.isAfter(Instant.now())) {
			sender.editMessage(chatId, messageId, "Это время уже прошло — выбери другую дату:",
					Keyboards.calendar(YearMonth.from(today), today));
			return;
		}
		draft.setFirstFireAt(chosen);
		draft.setNextFireAt(chosen);
		events.save(draft);
		setState(user, ChatState.NEW_AWAITING_SCHEDULE);
		sender.editMessage(chatId, messageId,
				"Первое срабатывание: " + chosen.atZone(zone).format(SUMMARY_FORMAT), null);
		sender.send(chatId, "Периодичность события?", Keyboards.schedulePresets());
	}

	/**
	 * The x/nav/day/hour steps shared by the /new and edit calendars: they edit the message in place
	 * to the next grid. Returns the picked instant only for the terminal "min" step (caller decides
	 * what to do with it); empty for every non-terminal or inert cell.
	 */
	private Optional<Instant> calendarStep(AppUser user, String[] parts, int messageId) {
		long chatId = user.getTelegramChatId();
		ZoneId zone = zone(user);
		switch (parts[1]) {
			case "nav" -> sender.editKeyboard(chatId, messageId,
					Keyboards.calendar(YearMonth.parse(parts[2]), LocalDate.now(zone)));
			case "day" -> {
				LocalDate date = LocalDate.parse(parts[2]);
				sender.editMessage(chatId, messageId,
						"📅 " + date.format(DATE_FORMAT) + " — выбери час:", Keyboards.hourGrid(date));
			}
			case "hour" -> {
				LocalDate date = LocalDate.parse(parts[2]);
				int hour = Integer.parseInt(parts[3]);
				sender.editMessage(chatId, messageId,
						"📅 " + date.format(DATE_FORMAT) + ", " + parts[3] + " ч — выбери минуты:",
						Keyboards.minuteGrid(date, hour));
			}
			case "min" -> {
				LocalDate date = LocalDate.parse(parts[2]);
				return Optional.of(ZonedDateTime.of(date,
						LocalTime.of(Integer.parseInt(parts[3]), Integer.parseInt(parts[4])), zone).toInstant());
			}
			default -> { /* "x" inert cell, or unknown */ }
		}
		return Optional.empty();
	}

	private void askNag(AppUser user) {
		setState(user, ChatState.NEW_AWAITING_NAG);
		sender.send(user.getTelegramChatId(), "Напоминать, пока событие не закрыто?", Keyboards.nagPresets());
	}

	private String summary(AppUser user, Event event) {
		String first = event.getFirstFireAt().atZone(zone(user)).format(SUMMARY_FORMAT);
		String nag = event.getNagIntervalMinutes() == null
				? "выключены"
				: "каждые " + event.getNagIntervalMinutes() + " мин, пока не нажмёшь «Готово»";
		return """
				Событие «%s» создано ✅
				Расписание: %s
				Первое срабатывание: %s
				Напоминания: %s""".formatted(event.getName(), ScheduleMath.describe(event), first, nag);
	}

	/** Abort an in-progress /new: drop the draft and clear the dialog state. */
	public void cancel(AppUser user) {
		events.findFirstByUserIdAndStatus(user.getId(), EventStatus.DRAFT).ifPresent(draft -> {
			occurrences.deleteByEventId(draft.getId());
			events.delete(draft);
		});
		setState(user, null);
	}

	/** True while the user is inside the /new wizard (any step), so /cancel is meaningful. */
	public static boolean isWizardState(ChatState state) {
		return state != null && switch (state) {
			case NEW_AWAITING_NAME, NEW_AWAITING_FIRST_FIRE, NEW_AWAITING_SCHEDULE,
					NEW_AWAITING_INTERVAL_MINUTES, NEW_AWAITING_INTERVAL_HOURS, NEW_AWAITING_NAG -> true;
			default -> false;
		};
	}

	// ===================== Editing an existing event (from /manage) =====================

	/** True while the user is mid-edit of an existing event, so /cancel is meaningful. */
	public static boolean isEditState(ChatState state) {
		return state != null && switch (state) {
			case EDIT_AWAITING_NAME, EDIT_AWAITING_FIRST_FIRE, EDIT_AWAITING_DAILY_TIME,
					EDIT_AWAITING_INTERVAL_MINUTES, EDIT_AWAITING_INTERVAL_HOURS -> true;
			default -> false;
		};
	}

	/** Abort an in-progress edit: drop the target and clear the dialog state (no data was committed yet). */
	public void cancelEdit(AppUser user) {
		clearEditing(user);
	}

	/** edit:*, esched:*, enag:* callbacks. The event id rides in parts[1]. */
	private void onEditCallback(AppUser user, CallbackQuery query, String[] parts) {
		long eventId;
		try {
			eventId = Long.parseLong(parts[1]);
		} catch (NumberFormatException e) {
			sender.answerCallback(query.getId(), "Ошибка");
			return;
		}
		Event event = editableEvent(user, eventId);
		if (event == null) {
			clearEditing(user);
			sender.answerCallback(query.getId(), "Уже неактуально");
			return;
		}
		switch (parts[0]) {
			case "edit" -> {
				if (parts.length == 2) {
					openEditMenu(user, event, query);
				} else {
					onEditMenu(user, event, parts[2], query);
				}
			}
			case "esched" -> onEditSchedulePreset(user, event, parts[2], query);
			case "enag" -> onEditNag(user, event, parts[2], query);
			default -> sender.answerCallback(query.getId(), "Неизвестная кнопка");
		}
	}

	/** "✏️ Изменить" on a /manage item: open the field picker as a fresh message (item stays intact). */
	private void openEditMenu(AppUser user, Event event, CallbackQuery query) {
		clearEditing(user); // no field chosen yet
		sender.answerCallback(query.getId(), null);
		sender.send(user.getTelegramChatId(),
				"Что изменить в «" + event.getName() + "»?", Keyboards.editMenu(event.getId()));
	}

	private void onEditMenu(AppUser user, Event event, String field, CallbackQuery query) {
		long chatId = user.getTelegramChatId();
		int messageId = query.getMessage().getMessageId();
		sender.answerCallback(query.getId(), null);
		switch (field) {
			case "name" -> {
				setEditing(user, event.getId(), ChatState.EDIT_AWAITING_NAME);
				sender.editMessage(chatId, messageId, "Текущее название: «" + event.getName() + "»", null);
				sender.send(chatId, "Новое название? (/cancel — отмена)");
			}
			case "nag" -> sender.editMessage(chatId, messageId,
					"Напоминания сейчас: " + nagLabel(event) + ". Выбери новые:",
					Keyboards.editNagPresets(event.getId()));
			case "sched" -> sender.editMessage(chatId, messageId,
					"Периодичность сейчас: " + ScheduleMath.describe(event) + ". Выбери новую:",
					Keyboards.editSchedulePresets(event.getId()));
			case "time" -> onEditTimeStart(user, event, messageId);
			case "cancel" -> {
				clearEditing(user);
				sender.editMessage(chatId, messageId, "✖ Редактирование отменено", null);
			}
			default -> sender.answerCallback(query.getId(), "Неизвестная кнопка");
		}
	}

	/** "🕒 Время": keep the schedule type, re-ask only the timing that type needs. */
	private void onEditTimeStart(AppUser user, Event event, int messageId) {
		long chatId = user.getTelegramChatId();
		switch (event.getScheduleType()) {
			case ONCE -> {
				setEditing(user, event.getId(), ChatState.EDIT_AWAITING_FIRST_FIRE);
				sender.editMessage(chatId, messageId, "Меняем время срабатывания.", null);
				sendCalendar(user);
			}
			case DAILY -> {
				setEditing(user, event.getId(), ChatState.EDIT_AWAITING_DAILY_TIME);
				sender.editMessage(chatId, messageId, "Текущее время: " + event.getTimeOfDay(), null);
				sender.send(chatId, "Во сколько ежедневно? Формат ЧЧ:ММ (/cancel — отмена)");
			}
			default -> sender.editMessage(chatId, messageId,
					"У интервального события фиксированного времени нет — измени интервал через «Периодичность».", null);
		}
	}

	/**
	 * "🔁 Периодичность": the type change is not committed here — we only route to the input step for
	 * the chosen type. The terminal step ({@link #onEditText}/{@link #onEditCalendar}) applies type and
	 * its parameter together, so an abandoned edit never leaves a half-changed schedule.
	 */
	private void onEditSchedulePreset(AppUser user, Event event, String preset, CallbackQuery query) {
		long chatId = user.getTelegramChatId();
		int messageId = query.getMessage().getMessageId();
		sender.answerCallback(query.getId(), null);
		switch (preset) {
			case "ONCE" -> {
				setEditing(user, event.getId(), ChatState.EDIT_AWAITING_FIRST_FIRE);
				sender.editMessage(chatId, messageId, "Тип: однократно. Выбери момент срабатывания:", null);
				sendCalendar(user);
			}
			case "DAILY" -> {
				setEditing(user, event.getId(), ChatState.EDIT_AWAITING_DAILY_TIME);
				sender.editMessage(chatId, messageId, "Тип: ежедневно.", null);
				sender.send(chatId, "Во сколько ежедневно? Формат ЧЧ:ММ (/cancel — отмена)");
			}
			case "EVERY_MIN" -> {
				setEditing(user, event.getId(), ChatState.EDIT_AWAITING_INTERVAL_MINUTES);
				sender.editMessage(chatId, messageId, "Тип: каждые N минут.", null);
				sender.send(chatId, "Каждые сколько минут? Напиши число. (/cancel — отмена)");
			}
			case "EVERY_HOUR" -> {
				setEditing(user, event.getId(), ChatState.EDIT_AWAITING_INTERVAL_HOURS);
				sender.editMessage(chatId, messageId, "Тип: каждые N часов.", null);
				sender.send(chatId, "Каждые сколько часов? Напиши число. (/cancel — отмена)");
			}
			default -> sender.answerCallback(query.getId(), "Неизвестный пресет");
		}
	}

	private void onEditNag(AppUser user, Event event, String value, CallbackQuery query) {
		int minutes;
		try {
			minutes = Integer.parseInt(value);
		} catch (NumberFormatException e) {
			sender.answerCallback(query.getId(), "Ошибка");
			return;
		}
		event.setNagIntervalMinutes(minutes == 0 ? null : minutes);
		events.save(event);
		clearEditing(user);
		sender.answerCallback(query.getId(), null);
		sender.editMessage(user.getTelegramChatId(), query.getMessage().getMessageId(),
				"🔔 Напоминания обновлены: " + nagLabel(event), null);
	}

	/** Text steps of an edit: new name, ONCE date/time, DAILY time-of-day, or interval. */
	private void onEditText(AppUser user, String text) {
		long chatId = user.getTelegramChatId();
		Event event = editingEvent(user);
		if (event == null) {
			clearEditing(user);
			sender.send(chatId, "Событие не найдено — открой /manage заново.");
			return;
		}
		switch (user.getChatState()) {
			case EDIT_AWAITING_NAME -> {
				if (text.isBlank() || text.length() > 200) {
					sender.send(chatId, "Название должно быть от 1 до 200 символов. Попробуй ещё раз.");
					return;
				}
				event.setName(text);
				events.save(event);
				clearEditing(user);
				sender.send(chatId, "✏️ Название обновлено: " + text);
			}
			case EDIT_AWAITING_FIRST_FIRE -> {
				Optional<Instant> parsed = TimeParsers.parseFirstFire(text, zone(user), Instant.now());
				if (parsed.isEmpty()) {
					sender.send(chatId, "Не понял. Примеры: 18:30 или 25.12 14:00");
					return;
				}
				event.setScheduleType(ScheduleType.ONCE);
				event.setFirstFireAt(parsed.get());
				applyNextFire(event, user);
				events.save(event);
				clearEditing(user);
				sendEditDone(user, event, "🕒 Время обновлено");
			}
			case EDIT_AWAITING_DAILY_TIME -> {
				Optional<LocalTime> parsed = TimeParsers.parseTimeOfDay(text);
				if (parsed.isEmpty()) {
					sender.send(chatId, "Формат времени — ЧЧ:ММ, например 09:30.");
					return;
				}
				event.setScheduleType(ScheduleType.DAILY);
				event.setTimeOfDay(parsed.get());
				applyNextFire(event, user);
				events.save(event);
				clearEditing(user);
				sendEditDone(user, event, "🔁 Расписание обновлено");
			}
			case EDIT_AWAITING_INTERVAL_MINUTES, EDIT_AWAITING_INTERVAL_HOURS -> {
				Integer value = parsePositiveInt(text);
				if (value == null || value > 10_000) {
					sender.send(chatId, "Напиши целое число, например 45.");
					return;
				}
				event.setScheduleType(ScheduleType.EVERY_N_MINUTES);
				event.setIntervalMinutes(
						user.getChatState() == ChatState.EDIT_AWAITING_INTERVAL_HOURS ? value * 60 : value);
				applyNextFire(event, user);
				events.save(event);
				clearEditing(user);
				sendEditDone(user, event, "🔁 Расписание обновлено");
			}
			default -> sender.send(chatId, "Выбери вариант кнопками выше 👆");
		}
	}

	/** Calendar picks for a ONCE date/time edit (the "time" and "periodicity→once" paths). */
	private void onEditCalendar(AppUser user, String[] parts, CallbackQuery query) {
		long chatId = user.getTelegramChatId();
		int messageId = query.getMessage().getMessageId();
		Event event = editingEvent(user);
		if (event == null || user.getChatState() != ChatState.EDIT_AWAITING_FIRST_FIRE) {
			sender.answerCallback(query.getId(), "Уже неактуально");
			return;
		}
		ZoneId zone = zone(user);
		LocalDate today = LocalDate.now(zone);
		sender.answerCallback(query.getId(), null);
		Optional<Instant> picked = calendarStep(user, parts, messageId);
		if (picked.isEmpty()) {
			return;
		}
		Instant chosen = picked.get();
		if (!chosen.isAfter(Instant.now())) {
			sender.editMessage(chatId, messageId, "Это время уже прошло — выбери другую дату:",
					Keyboards.calendar(YearMonth.from(today), today));
			return;
		}
		event.setScheduleType(ScheduleType.ONCE);
		event.setFirstFireAt(chosen);
		applyNextFire(event, user);
		events.save(event);
		clearEditing(user);
		sender.editMessage(chatId, messageId, "✅ Однократно: " + chosen.atZone(zone).format(SUMMARY_FORMAT), null);
	}

	/** Recompute next_fire_at after a schedule/time change — but only for a live (ACTIVE) event. */
	private void applyNextFire(Event event, AppUser user) {
		if (event.getStatus() != EventStatus.ACTIVE) {
			// Paused: drop any stale firing so resume (ScheduleMath.reactivate) recomputes from the new schedule.
			event.setNextFireAt(null);
			return;
		}
		event.setNextFireAt(event.getScheduleType() == ScheduleType.ONCE
				? event.getFirstFireAt()
				: ScheduleMath.nextFireAfter(event, Instant.now(), user));
	}

	private void sendEditDone(AppUser user, Event event, String prefix) {
		String next = event.getNextFireAt() == null
				? "—" : event.getNextFireAt().atZone(zone(user)).format(NEXT_FORMAT);
		String status = event.getStatus() == EventStatus.PAUSED ? " (на паузе)" : "";
		sender.send(user.getTelegramChatId(), "%s: «%s»%s\n%s, следующее: %s".formatted(
				prefix, event.getName(), status, ScheduleMath.describe(event), next));
	}

	/** ACTIVE/PAUSED event owned by the user; null if missing, foreign or already finished. */
	private Event editableEvent(AppUser user, long eventId) {
		return events.findById(eventId)
				.filter(e -> e.getUser().getId().equals(user.getId()))
				.filter(e -> e.getStatus() == EventStatus.ACTIVE || e.getStatus() == EventStatus.PAUSED)
				.orElse(null);
	}

	/** The event referenced by the user's editing_event_id, re-validated. */
	private Event editingEvent(AppUser user) {
		Long id = user.getEditingEventId();
		return id == null ? null : editableEvent(user, id);
	}

	private void setEditing(AppUser user, Long eventId, ChatState state) {
		user.setEditingEventId(eventId);
		user.setChatState(state);
		users.save(user);
	}

	private void clearEditing(AppUser user) {
		user.setEditingEventId(null);
		user.setChatState(null);
		users.save(user);
	}

	private static String nagLabel(Event event) {
		return event.getNagIntervalMinutes() == null
				? "выключены" : "каждые " + event.getNagIntervalMinutes() + " мин";
	}

	private Event draft(AppUser user) {
		return events.findFirstByUserIdAndStatus(user.getId(), EventStatus.DRAFT).orElse(null);
	}

	private void setState(AppUser user, ChatState state) {
		user.setChatState(state);
		users.save(user);
	}

	private static ZoneId zone(AppUser user) {
		return ZoneId.of(user.getTimezone());
	}

	private static Integer parsePositiveInt(String text) {
		try {
			int value = Integer.parseInt(text.trim());
			return value > 0 ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
