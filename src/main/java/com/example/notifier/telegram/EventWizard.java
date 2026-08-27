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

/** The /new dialog: name → first firing → schedule preset → reminder preset → active event. */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventWizard {

	private static final DateTimeFormatter SUMMARY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

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
		switch (parts[1]) {
			case "x" -> { /* inert cell */ }
			case "nav" -> sender.editKeyboard(chatId, messageId, Keyboards.calendar(YearMonth.parse(parts[2]), today));
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
				Instant chosen = ZonedDateTime.of(date,
						LocalTime.of(Integer.parseInt(parts[3]), Integer.parseInt(parts[4])), zone).toInstant();
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
			default -> log.warn("Unknown calendar callback: {}", query.getData());
		}
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
