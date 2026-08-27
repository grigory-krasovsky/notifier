package com.example.notifier.telegram;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeyboardsCalendarTest {

	private static List<String> callbackData(InlineKeyboardMarkup markup) {
		return markup.getKeyboard().stream().flatMap(List::stream)
				.map(InlineKeyboardButton::getCallbackData).toList();
	}

	@Test
	void weekStartsMondayAndDaysAreFullyOffset() {
		// 2026-08-01 is a Saturday -> Monday-first index 5
		LocalDate today = LocalDate.of(2026, 8, 1);
		InlineKeyboardMarkup cal = Keyboards.calendar(YearMonth.of(2026, 8), today);
		List<InlineKeyboardRow> rows = cal.getKeyboard();

		assertThat(rows.get(1)).extracting(InlineKeyboardButton::getText)
				.containsExactly("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс");

		InlineKeyboardRow firstWeek = rows.get(2);
		assertThat(firstWeek.get(0).getCallbackData()).isEqualTo("cal:x");            // Monday lead blank
		assertThat(firstWeek.get(5).getCallbackData()).isEqualTo("cal:day:2026-08-01"); // Saturday = the 1st
	}

	@Test
	void pastDaysInertAndBackArrowHiddenInCurrentMonth() {
		LocalDate today = LocalDate.of(2026, 8, 10);
		List<String> data = callbackData(Keyboards.calendar(YearMonth.of(2026, 8), today));

		assertThat(data).doesNotContain("cal:day:2026-08-05"); // past
		assertThat(data).contains("cal:day:2026-08-10");       // today, selectable
		assertThat(data).doesNotContain("cal:nav:2026-07");    // cannot page into a fully-past month
		assertThat(data).contains("cal:nav:2026-09");          // forward navigation offered
	}

	@Test
	void todayIsMarkedButStillSelectable() {
		LocalDate today = LocalDate.of(2026, 8, 10);
		InlineKeyboardMarkup cal = Keyboards.calendar(YearMonth.of(2026, 8), today);
		InlineKeyboardButton todayBtn = cal.getKeyboard().stream().flatMap(List::stream)
				.filter(b -> "cal:day:2026-08-10".equals(b.getCallbackData()))
				.findFirst().orElseThrow();
		assertThat(todayBtn.getText()).isEqualTo("[10]");
	}

	@Test
	void futureMonthOffersBackNavigation() {
		LocalDate today = LocalDate.of(2026, 8, 10);
		assertThat(callbackData(Keyboards.calendar(YearMonth.of(2026, 9), today)))
				.contains("cal:nav:2026-08");
	}

	@Test
	void hourAndMinuteGridsEncodeTheSelection() {
		LocalDate date = LocalDate.of(2026, 8, 20);
		List<String> hours = callbackData(Keyboards.hourGrid(date));
		assertThat(hours).filteredOn(d -> d.startsWith("cal:hour:")).hasSize(24);
		assertThat(hours).contains("cal:hour:2026-08-20:09");

		List<String> minutes = callbackData(Keyboards.minuteGrid(date, 9));
		assertThat(minutes).filteredOn(d -> d.startsWith("cal:min:")).hasSize(12);
		assertThat(minutes).contains("cal:min:2026-08-20:09:30");
	}

	@Test
	void everyWizardKeyboardOffersCancel() {
		LocalDate today = LocalDate.of(2026, 8, 10);
		assertThat(callbackData(Keyboards.calendar(YearMonth.of(2026, 8), today))).contains("new:cancel");
		assertThat(callbackData(Keyboards.hourGrid(today))).contains("new:cancel");
		assertThat(callbackData(Keyboards.minuteGrid(today, 9))).contains("new:cancel");
		assertThat(callbackData(Keyboards.schedulePresets())).contains("new:cancel");
		assertThat(callbackData(Keyboards.nagPresets())).contains("new:cancel");
	}
}
