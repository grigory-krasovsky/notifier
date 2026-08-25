package com.example.notifier.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramSender {

	private final TelegramClient telegramClient;

	public Integer send(long chatId, String text) {
		return send(chatId, text, null);
	}

	/** Returns the sent message id, or null when sending failed. */
	public Integer send(long chatId, String text, InlineKeyboardMarkup markup) {
		try {
			Message message = telegramClient.execute(
					SendMessage.builder().chatId(chatId).text(text).replyMarkup(markup).build());
			return message.getMessageId();
		} catch (TelegramApiException e) {
			log.error("Failed to send message to chat {}", chatId, e);
			return null;
		}
	}

	public void removeButtons(long chatId, Integer messageId) {
		if (messageId == null) {
			return;
		}
		try {
			telegramClient.execute(EditMessageReplyMarkup.builder()
					.chatId(chatId).messageId(messageId).replyMarkup(null).build());
		} catch (TelegramApiException e) {
			log.debug("Cannot remove buttons from message {} in chat {}", messageId, chatId, e);
		}
	}

	public void answerCallback(String callbackQueryId, String text) {
		try {
			telegramClient.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).text(text).build());
		} catch (TelegramApiException e) {
			log.debug("Cannot answer callback {}", callbackQueryId, e);
		}
	}
}
