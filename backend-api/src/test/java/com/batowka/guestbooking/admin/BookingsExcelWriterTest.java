package com.batowka.guestbooking.admin;

import com.batowka.guestbooking.booking.AdminBookingService.BookingRow;
import com.batowka.guestbooking.booking.BookingStatus;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookingsExcelWriterTest {

    private final BookingsExcelWriter writer = new BookingsExcelWriter();

    @Test
    void собираетЛистСоВсемиКолонкамиИТипами() throws Exception {
        List<BookingRow> rows = List.of(
                new BookingRow(1, "Арай", "+77473563534",
                        LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12),
                        BookingStatus.CONFIRMED, "приедем вдвоём"),
                new BookingRow(2, "Маша", "+79990000001",
                        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 8),
                        BookingStatus.COMPLETED, null),
                new BookingRow(3, "Батыр", "+77001234567",
                        LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2),
                        BookingStatus.CANCELLED, null));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(writer.write(rows)))) {
            Sheet sheet = wb.getSheet("Брони");
            assertThat(sheet).isNotNull();

            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Гость");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Телефон");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Заезд");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Выезд");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Ночей");
            assertThat(header.getCell(5).getStringCellValue()).isEqualTo("Статус");
            assertThat(header.getCell(6).getStringCellValue()).isEqualTo("Комментарий");

            Row first = sheet.getRow(1);
            assertThat(first.getCell(0).getStringCellValue()).isEqualTo("Арай");
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("+77473563534");
            // даты — настоящие Excel-даты, не текст
            assertThat(first.getCell(2).getLocalDateTimeCellValue().toLocalDate())
                    .isEqualTo(LocalDate.of(2026, 9, 10));
            assertThat(first.getCell(3).getLocalDateTimeCellValue().toLocalDate())
                    .isEqualTo(LocalDate.of(2026, 9, 12));
            assertThat(first.getCell(4).getNumericCellValue()).isEqualTo(2);
            assertThat(first.getCell(5).getStringCellValue()).isEqualTo("подтверждена");
            assertThat(first.getCell(6).getStringCellValue()).isEqualTo("приедем вдвоём");

            assertThat(sheet.getRow(2).getCell(5).getStringCellValue()).isEqualTo("завершена");
            // null-комментарий — пустая ячейка (createCell не вызывался)
            Cell emptyComment = sheet.getRow(2).getCell(6);
            assertThat(emptyComment == null || emptyComment.getStringCellValue().isEmpty()).isTrue();
            assertThat(sheet.getRow(3).getCell(5).getStringCellValue()).isEqualTo("отменена");
        }
    }
}
