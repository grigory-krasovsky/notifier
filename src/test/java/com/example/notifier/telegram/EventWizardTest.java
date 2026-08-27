package com.example.notifier.telegram;

import com.example.notifier.domain.AppUser;
import com.example.notifier.domain.ChatState;
import com.example.notifier.domain.EventStatus;
import com.example.notifier.repository.AppUserRepository;
import com.example.notifier.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class EventWizardTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@MockitoBean
	TelegramSender sender;

	@Autowired
	EventWizard wizard;
	@Autowired
	AppUserRepository users;
	@Autowired
	EventRepository events;

	@Test
	void cancelAbortsTheDraftAndClearsState() {
		AppUser user = new AppUser();
		user.setTelegramChatId(77L);
		user.setTimezone("+00:00");
		users.save(user);

		wizard.start(user);
		assertThat(events.findFirstByUserIdAndStatus(user.getId(), EventStatus.DRAFT)).isPresent();
		assertThat(user.getChatState()).isEqualTo(ChatState.NEW_AWAITING_NAME);

		wizard.cancel(user);
		assertThat(events.findFirstByUserIdAndStatus(user.getId(), EventStatus.DRAFT)).isEmpty();
		assertThat(user.getChatState()).isNull();
	}

	@Test
	void isWizardStateCoversEveryNewStepButNotOnboarding() {
		assertThat(EventWizard.isWizardState(ChatState.NEW_AWAITING_NAME)).isTrue();
		assertThat(EventWizard.isWizardState(ChatState.NEW_AWAITING_NAG)).isTrue();
		assertThat(EventWizard.isWizardState(ChatState.AWAITING_TIMEZONE)).isFalse();
		assertThat(EventWizard.isWizardState(null)).isFalse();
	}
}
