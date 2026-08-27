package com.example.notifier.telegram;

import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;

import java.util.List;

/** Commands advertised in Telegram's "/" menu; registered via setMyCommands on startup. */
public final class BotCommands {

	private BotCommands() {
	}

	public static List<BotCommand> all() {
		return List.of(
				cmd("new", "Создать событие"),
				cmd("list", "Мои события — пауза, завершение, удаление"),
				cmd("help", "Что я умею"),
				cmd("start", "Перенастроить часовой пояс"));
	}

	private static BotCommand cmd(String command, String description) {
		return BotCommand.builder().command(command).description(description).build();
	}
}
