package com.example.notifier.telegram;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;

import static org.assertj.core.api.Assertions.assertThat;

class BotCommandsTest {

	@Test
	void exposesCoreCommandsWithDescriptions() {
		assertThat(BotCommands.all()).extracting(BotCommand::getCommand)
				.containsExactly("new", "list", "help", "start");
		// Bot API: names lowercase, descriptions 1-256 chars
		assertThat(BotCommands.all()).allSatisfy(c -> {
			assertThat(c.getCommand()).matches("[a-z0-9_]{1,32}");
			assertThat(c.getDescription()).isNotBlank().hasSizeLessThanOrEqualTo(256);
		});
	}
}
