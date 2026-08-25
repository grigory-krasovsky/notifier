package com.example.notifier.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotifierBot implements LongPollingSingleThreadUpdateConsumer {

	private final UpdateHandler handler;

	@Override
	public void consume(Update update) {
		try {
			handler.handle(update);
		} catch (Exception e) {
			log.error("Failed to handle update", e);
		}
	}
}
