package com.example.notifier.telegram;

import com.example.notifier.domain.Event;
import com.example.notifier.domain.EventStatus;
import com.example.notifier.domain.ScheduleType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleTableTest {

	private static Event event(String name, EventStatus status, ScheduleType type) {
		Event event = new Event();
		event.setName(name);
		event.setStatus(status);
		event.setScheduleType(type);
		return event;
	}

	@Test
	void rendersOneAlignedTableWithNextAndStatus() {
		Event active = event("Позвонить", EventStatus.ACTIVE, ScheduleType.ONCE);
		active.setNextFireAt(Instant.parse("2026-08-31T14:00:00Z"));
		Event paused = event("Оплатить счёт", EventStatus.PAUSED, ScheduleType.EVERY_N_MINUTES);
		paused.setIntervalMinutes(120);
		paused.setNextFireAt(Instant.parse("2026-08-31T10:00:00Z"));

		List<String> messages = ScheduleTable.render(List.of(active, paused), ZoneOffset.UTC);

		assertThat(messages).hasSize(1);
		String msg = messages.get(0);
		assertThat(msg).startsWith("<pre>").endsWith("</pre>");
		assertThat(msg).contains("Событие").contains("Следующее");
		assertThat(msg).doesNotContain("Расписание");                        // schedule column removed
		assertThat(msg).contains("Позвонить" + " ".repeat(9) + "31.08 14:00"); // name padded, next aligned
		assertThat(msg).contains("пауза");                                   // paused shown instead of a date
	}

	@Test
	void truncatesLongNamesAndEscapesHtml() {
		Event longName = event("Очень длинное название события", EventStatus.ACTIVE, ScheduleType.ONCE);
		Event html = event("A<b>C", EventStatus.ACTIVE, ScheduleType.ONCE);

		String msg = ScheduleTable.render(List.of(longName, html), ZoneOffset.UTC).get(0);

		assertThat(msg).contains("…");            // long name truncated
		assertThat(msg).contains("A&lt;b&gt;C");  // angle brackets escaped for HTML parse mode
		assertThat(msg).doesNotContain("<b>");    // raw tag never leaks
	}
}
