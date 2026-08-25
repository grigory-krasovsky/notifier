package com.example.notifier.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notifier.telegram")
public record TelegramProperties(String botToken, String proxyHost, Integer proxyPort, String proxyType) {

	public boolean proxyConfigured() {
		return proxyHost != null && !proxyHost.isBlank() && proxyPort != null && proxyPort > 0;
	}
}
