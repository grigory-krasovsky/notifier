package com.example.notifier.telegram;

import com.example.notifier.domain.AppUser;
import com.example.notifier.domain.ChatState;
import com.example.notifier.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotifierBot implements LongPollingSingleThreadUpdateConsumer {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");

	private final TelegramClient telegramClient;
	private final AppUserRepository users;

	@Override
	public void consume(Update update) {
		if (!update.hasMessage() || !update.getMessage().hasText()) {
			return;
		}
		long chatId = update.getMessage().getChatId();
		String text = update.getMessage().getText().trim();
		log.info("Update from chat {}: {}", chatId, text);
		try {
			handle(chatId, text);
		} catch (Exception e) {
			log.error("Failed to handle update from chat {}", chatId, e);
			send(chatId, "Что-то пошло не так, попробуй ещё раз.");
		}
	}

	private void handle(long chatId, String text) {
		AppUser user = users.findByTelegramChatId(chatId).orElse(null);
		if (text.startsWith("/start")) {
			onStart(chatId, user);
		} else if (user != null && user.getChatState() == ChatState.AWAITING_TIMEZONE) {
			onTimezoneAnswer(chatId, user, text);
		} else {
			send(chatId, "Пока я умею только /start (настройка). Команды /new и /list уже в разработке.");
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
		send(chatId, """
				Привет! Я буду напоминать тебе о событиях.
				Сначала настроим часовой пояс: напиши, сколько у тебя сейчас времени, в формате ЧЧ:ММ (например, 14:30).""");
	}

	private void onTimezoneAnswer(long chatId, AppUser user, String text) {
		LocalTime userTime;
		try {
			userTime = LocalTime.parse(text, TIME_FORMAT);
		} catch (DateTimeParseException e) {
			send(chatId, "Не понял. Напиши текущее время в формате ЧЧ:ММ, например 14:30.");
			return;
		}
		ZoneOffset offset = TimezoneResolver.resolve(userTime, Instant.now());
		user.setTimezone(offset.getId());
		user.setChatState(null);
		users.save(user);
		send(chatId, """
				Готово! Твой часовой пояс: UTC%s.
				Рабочие часы по умолчанию: %s–%s — вне их я не шлю напоминания.
				Дальше: /new — создать событие (скоро).""".formatted(offsetLabel(offset), user.getWorkStart(), user.getWorkEnd()));
	}

	private static String offsetLabel(ZoneOffset offset) {
		return offset == ZoneOffset.UTC ? "+00:00" : offset.getId();
	}

	private void send(long chatId, String text) {
		try {
			telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build());
		} catch (TelegramApiException e) {
			log.error("Failed to send message to chat {}", chatId, e);
		}
	}
}
