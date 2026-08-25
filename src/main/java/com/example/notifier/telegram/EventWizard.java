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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/** The /new dialog: name → first firing → schedule preset → reminder preset → active event. */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventWizard {

	private static final DateTimeFormatter SUMMARY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

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
		sender.send(user.getTelegramChatId(), "Как назовём событие?");
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
				sender.send(chatId, """
						Когда первое срабатывание?
						Напиши время ЧЧ:ММ (сегодня; если уже прошло — завтра) или дату и время: ДД.ММ ЧЧ:ММ.""");
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
		Event draft = draft(user);
		if (draft == null) {
			sender.answerCallback(query.getId(), "Черновика нет — начни с /new");
			return;
		}
		String[] parts = query.getData().split(":");
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
					sender.send(chatId, "Каждые сколько минут? Напиши число.");
				}
				case "EVERY_HOUR" -> {
					draft.setScheduleType(ScheduleType.EVERY_N_MINUTES);
					events.save(draft);
					setState(user, ChatState.NEW_AWAITING_INTERVAL_HOURS);
					sender.send(chatId, "Каждые сколько часов? Напиши число.");
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
