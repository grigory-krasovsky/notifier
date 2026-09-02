package com.example.notifier.telegram;

import com.example.notifier.domain.AppUser;
import com.example.notifier.domain.ChatState;
import com.example.notifier.domain.Event;
import com.example.notifier.domain.EventStatus;
import com.example.notifier.domain.ScheduleType;
import com.example.notifier.repository.AppUserRepository;
import com.example.notifier.repository.EventRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
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

	@Test
	void isEditStateCoversEditStepsOnly() {
		assertThat(EventWizard.isEditState(ChatState.EDIT_AWAITING_NAME)).isTrue();
		assertThat(EventWizard.isEditState(ChatState.EDIT_AWAITING_INTERVAL_HOURS)).isTrue();
		assertThat(EventWizard.isEditState(ChatState.NEW_AWAITING_NAME)).isFalse();
		assertThat(EventWizard.isEditState(null)).isFalse();
	}

	@Test
	void editNameViaTextUpdatesAndClearsState() {
		AppUser user = savedUser(88L);
		Event event = savedEvent(user, "Старое имя");
		beginEdit(user, event, ChatState.EDIT_AWAITING_NAME);

		wizard.onText(user, "Новое имя");

		assertThat(events.findById(event.getId()).orElseThrow().getName()).isEqualTo("Новое имя");
		assertThat(user.getChatState()).isNull();
		assertThat(user.getEditingEventId()).isNull();
	}

	@Test
	void editIntervalViaTextSwitchesTypeAndRecomputesNextFire() {
		AppUser user = savedUser(89L);
		Event event = savedEvent(user, "Интервальное"); // starts ONCE
		beginEdit(user, event, ChatState.EDIT_AWAITING_INTERVAL_HOURS);

		wizard.onText(user, "2");

		Event updated = events.findById(event.getId()).orElseThrow();
		assertThat(updated.getScheduleType()).isEqualTo(ScheduleType.EVERY_N_MINUTES);
		assertThat(updated.getIntervalMinutes()).isEqualTo(120);
		assertThat(updated.getNextFireAt()).isNotNull();
		assertThat(user.getChatState()).isNull();
	}

	@Test
	void editDailyTimeViaTextSetsTypeTimeAndNextFire() {
		AppUser user = savedUser(90L);
		Event event = savedEvent(user, "Ежедневное"); // starts ONCE
		beginEdit(user, event, ChatState.EDIT_AWAITING_DAILY_TIME);

		wizard.onText(user, "07:45");

		Event updated = events.findById(event.getId()).orElseThrow();
		assertThat(updated.getScheduleType()).isEqualTo(ScheduleType.DAILY);
		assertThat(updated.getTimeOfDay()).isEqualTo(LocalTime.of(7, 45));
		assertThat(updated.getNextFireAt()).isNotNull();
	}

	private AppUser savedUser(long chatId) {
		AppUser user = new AppUser();
		user.setTelegramChatId(chatId);
		user.setTimezone("+00:00");
		return users.save(user);
	}

	private Event savedEvent(AppUser user, String name) {
		Event event = new Event();
		event.setUser(user);
		event.setName(name);
		event.setStatus(EventStatus.ACTIVE);
		event.setScheduleType(ScheduleType.ONCE);
		return events.save(event);
	}

	private void beginEdit(AppUser user, Event event, ChatState state) {
		user.setEditingEventId(event.getId());
		user.setChatState(state);
		users.save(user);
	}
}
