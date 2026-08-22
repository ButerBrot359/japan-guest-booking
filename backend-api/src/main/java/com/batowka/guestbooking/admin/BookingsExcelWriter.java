package com.batowka.guestbooking.admin;

import com.batowka.guestbooking.booking.AdminBookingService.BookingRow;
import com.batowka.guestbooking.booking.BookingStatus;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Собирает xlsx со всеми бронями — выгрузка владельцу с вкладки «Брони». */
@Component
public class BookingsExcelWriter {

    private static final String[] HEADERS =
            {"Гость", "Телефон", "Заезд", "Выезд", "Ночей", "Статус", "Комментарий"};

    public byte[] write(List<BookingRow> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Брони");

            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(bold);

            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.createDataFormat().getFormat("dd.mm.yyyy"));

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
            }

            int r = 1;
            for (BookingRow b : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(b.guestName());
                row.createCell(1).setCellValue(b.guestPhone());
                Cell checkIn = row.createCell(2);
                checkIn.setCellValue(b.checkIn());
                checkIn.setCellStyle(dateStyle);
                Cell checkOut = row.createCell(3);
                checkOut.setCellValue(b.checkOut());
                checkOut.setCellStyle(dateStyle);
                row.createCell(4).setCellValue(ChronoUnit.DAYS.between(b.checkIn(), b.checkOut()));
                row.createCell(5).setCellValue(statusRu(b.status()));
                if (b.comment() != null) {
                    row.createCell(6).setCellValue(b.comment());
                }
            }
            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String statusRu(BookingStatus status) {
        return switch (status) {
            case CONFIRMED -> "подтверждена";
            case CANCELLED -> "отменена";
            case COMPLETED -> "завершена";
        };
    }
}
