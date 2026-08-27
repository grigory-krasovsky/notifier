package com.example.notifier.scheduler;

import com.example.notifier.domain.AppUser;
import com.example.notifier.domain.Event;
import com.example.notifier.domain.EventStatus;
import com.example.notifier.domain.Occurrence;
import com.example.notifier.domain.OccurrenceStatus;
import com.example.notifier.domain.ScheduleType;
import com.example.notifier.repository.EventRepository;
import com.example.notifier.repository.OccurrenceRepository;
import com.example.notifier.telegram.Keyboards;
import com.example.notifier.telegram.TelegramSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationScheduler {

	private final EventRepository events;
	private final OccurrenceRepository occurrences;
	private final TelegramSender sender;

	@Scheduled(fixedDelay = 30, initialDelay = 15, timeUnit = TimeUnit.SECONDS)
	@Transactional
	public void tick() {
		Instant now = Instant.now();
		fireDueEvents(now);
		sendDueReminders(now);
	}

	private void fireDueEvents(Instant now) {
		for (Event event : events.findByStatusAndNextFireAtLessThanEqual(EventStatus.ACTIVE, now)) {
			AppUser user = event.getUser();
			long chatId = user.getTelegramChatId();
			// "no debt" rule: a new firing replaces the still-open previous one
			occurrences.findByEventIdAndStatus(event.getId(), OccurrenceStatus.OPEN).ifPresent(open -> {
				open.setStatus(OccurrenceStatus.SUPERSEDED);
				open.setNextReminderAt(null);
				// Flush the supersede UPDATE before the new OPEN row is inserted below.
				// Otherwise Hibernate orders the INSERT ahead of the UPDATE, two OPEN
				// occurrences briefly coexist, and uq_occurrence_open_per_event fails.
				occurrences.saveAndFlush(open);
				Integer openMsg = toMessageId(open.getTelegramMessageId());
				if (openMsg != null) {
					sender.editMessage(chatId, openMsg, "⏭ " + event.getName() + " — заменено новым", null);
				}
			});

			Occurrence occurrence = new Occurrence();
			occurrence.setEvent(event);
			occurrence.setFiredAt(now);
			occurrences.save(occurrence);

			Integer messageId = sender.send(chatId, "🔔 " + event.getName(),
					Keyboards.notification(occurrence.getId(), finishableEventId(event)));
			occurrence.setTelegramMessageId(messageId == null ? null : messageId.longValue());
			if (event.getNagIntervalMinutes() != null) {
				occurrence.setNextReminderAt(ScheduleMath.deferIntoWorkingHours(
						now.plus(event.getNagIntervalMinutes(), ChronoUnit.MINUTES), user));
			}

			event.setNextFireAt(ScheduleMath.nextFireAfter(event, now, user));
			log.info("Fired event {} ({}), next fire at {}", event.getId(), event.getName(), event.getNextFireAt());
		}
	}

	private void sendDueReminders(Instant now) {
		for (Occurrence occurrence : occurrences.findByStatusAndNextReminderAtLessThanEqual(OccurrenceStatus.OPEN, now)) {
			Event event = occurrence.getEvent();
			AppUser user = event.getUser();
			long chatId = user.getTelegramChatId();
			// Keep one live message per occurrence: drop the previous ping before sending a fresh one.
			// A fresh message (not an edit) is deliberate — editing in place would not re-notify.
			Integer previous = toMessageId(occurrence.getTelegramMessageId());
			if (previous != null && !sender.deleteMessage(chatId, previous)) {
				sender.removeButtons(chatId, previous); // older than 48h: undeletable, at least strip stale buttons
			}
			Integer messageId = sender.send(chatId, "🔁 Напоминание: " + event.getName(),
					Keyboards.notification(occurrence.getId(), finishableEventId(event)));
			occurrence.setTelegramMessageId(messageId == null ? null : messageId.longValue());
			Integer nag = event.getNagIntervalMinutes();
			occurrence.setNextReminderAt(nag == null ? null
					: ScheduleMath.deferIntoWorkingHours(now.plus(nag, ChronoUnit.MINUTES), user));
		}
	}

	private static Long finishableEventId(Event event) {
		return event.getScheduleType() == ScheduleType.ONCE ? null : event.getId();
	}

	private static Integer toMessageId(Long stored) {
		return stored == null ? null : stored.intValue();
	}
}
