package com.example.notifier.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
@Slf4j
public class TelegramBotConfig {

	@Bean(destroyMethod = "close")
	public TelegramBotsLongPollingApplication botsApplication(TelegramProperties properties) {
		return new TelegramBotsLongPollingApplication(ObjectMapper::new, () -> buildHttpClient(properties));
	}

	@Bean
	public TelegramClient telegramClient(TelegramProperties properties) {
		return new OkHttpTelegramClient(buildHttpClient(properties), properties.botToken());
	}

	@Bean
	public ApplicationRunner telegramBotRegistrar(TelegramBotsLongPollingApplication botsApplication,
			TelegramProperties properties, NotifierBot bot, TelegramSender sender) {
		return args -> {
			if (properties.botToken() == null || properties.botToken().isBlank()) {
				log.warn("TELEGRAM_BOT_TOKEN is not set - Telegram bot is disabled");
				return;
			}
			try {
				botsApplication.registerBot(properties.botToken(), bot);
				sender.setCommands(BotCommands.all());
				log.info("Telegram bot registered, long polling started");
			} catch (Exception e) {
				log.error("Telegram bot registration failed - the app keeps running, but the bot is DOWN. "
						+ "Check network access to api.telegram.org from this environment.", e);
			}
		};
	}

	/** Read timeout must exceed the long-polling getUpdates timeout (50 s). */
	private OkHttpClient buildHttpClient(TelegramProperties properties) {
		OkHttpClient.Builder builder = new OkHttpClient.Builder()
				.connectTimeout(Duration.ofSeconds(30))
				.readTimeout(Duration.ofSeconds(75))
				.writeTimeout(Duration.ofSeconds(30));
		if (properties.proxyConfigured()) {
			Proxy.Type type = "HTTP".equalsIgnoreCase(properties.proxyType()) ? Proxy.Type.HTTP : Proxy.Type.SOCKS;
			builder.proxy(new Proxy(type, new InetSocketAddress(properties.proxyHost(), properties.proxyPort())));
			log.info("Telegram traffic goes via {} proxy {}:{}", type, properties.proxyHost(), properties.proxyPort());
		}
		return builder.build();
	}
}
