package com.example.notifier.scheduler;

import com.example.notifier.domain.AppUser;
import com.example.notifier.domain.Event;
import com.example.notifier.domain.EventStatus;
import com.example.notifier.domain.Occurrence;
import com.example.notifier.domain.OccurrenceStatus;
import com.example.notifier.domain.ScheduleType;
import com.example.notifier.repository.AppUserRepository;
import com.example.notifier.repository.EventRepository;
import com.example.notifier.repository.OccurrenceRepository;
import com.example.notifier.telegram.TelegramSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class NotificationSchedulerTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@MockitoBean
	TelegramSender sender;

	@Autowired
	NotificationScheduler scheduler;
	@Autowired
	AppUserRepository users;
	@Autowired
	EventRepository events;
	@Autowired
	OccurrenceRepository occurrences;

	/**
	 * A daily event fires again while the previous occurrence is still OPEN. The old one must be
	 * superseded and a new one created without tripping uq_occurrence_open_per_event.
	 */
	@Test
	void refiresDailyEventWhilePreviousOccurrenceStillOpen() {
		AppUser user = new AppUser();
		user.setTelegramChatId(42L);
		user.setTimezone("+00:00");
		user.setWorkStart(LocalTime.of(9, 0));
		user.setWorkEnd(LocalTime.of(22, 0));
		users.save(user);

		Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
		Event event = new Event();
		event.setUser(user);
		event.setName("Таблетки");
		event.setStatus(EventStatus.ACTIVE);
		event.setScheduleType(ScheduleType.DAILY);
		event.setTimeOfDay(LocalTime.of(10, 0));
		event.setFirstFireAt(past);
		event.setNextFireAt(past);
		events.save(event);

		Occurrence stillOpen = new Occurrence();
		stillOpen.setEvent(event);
		stillOpen.setFiredAt(past);
		stillOpen.setStatus(OccurrenceStatus.OPEN);
		stillOpen.setTelegramMessageId(1000L);
		occurrences.save(stillOpen);

		Instant beforeTick = Instant.now();
		scheduler.tick();

		List<Occurrence> all = occurrences.findAll();
		assertThat(all).hasSize(2);
		assertThat(occurrences.findByEventIdAndStatus(event.getId(), OccurrenceStatus.OPEN)).isPresent();
		assertThat(all).filteredOn(o -> o.getStatus() == OccurrenceStatus.SUPERSEDED)
				.singleElement()
				.satisfies(o -> assertThat(o.getId()).isEqualTo(stillOpen.getId()));

		Event reloaded = events.findById(event.getId()).orElseThrow();
		assertThat(reloaded.getNextFireAt()).isAfter(beforeTick);
	}
}
