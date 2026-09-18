package quest.server.classes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import quest.server.config.ApiException;

/**
 * Reading a class roster out of the file a school already has: CSV (any of `,`, `;` or a tab) or XLSX, columns
 * `name,parentEmail` (`docs/prompts/dashboard-first-one-school.md` §3).
 *
 * <p>It <strong>parses and nothing else</strong> — every line comes back, blank cells and malformed addresses
 * included, with the file's own row number, so {@link RosterService} can mark each one `new`, `duplicate` or
 * `invalid` and the Admin can see in the preview exactly which line of her spreadsheet she has to fix. A file that
 * cannot be opened at all is the only 400.
 */
@Component
public class RosterImport {
    /** A roster is a class, not a database: a file larger than this is a mistake, and is refused as one. */
    static final int MAX_ROWS = 2_000;
    private static final List<String> HEADER_NAMES = List.of("name", "full name", "fullname", "child", "student");

    /** One line as the file has it; `line` is 1-based and counts the header, so it matches what the Admin sees. */
    public record Line(int line, String name, String parentEmail) {}

    public List<Line> read(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            var rows = name.endsWith(".xlsx") || name.endsWith(".xls") ? spreadsheet(file.getBytes()) : csv(file.getBytes());
            if (rows.size() > MAX_ROWS) throw ApiException.badRequest("That file has more than " + MAX_ROWS + " rows.");
            return rows;
        } catch (IOException e) {
            throw ApiException.badRequest("That file could not be read — save it again as CSV or XLSX.");
        }
    }

    private List<Line> csv(byte[] bytes) throws IOException {
        var out = new ArrayList<Line>();
        try (var reader = new java.io.BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String raw; int number = 0;
            while ((raw = reader.readLine()) != null) {
                number++;
                String line = number == 1 ? raw.replace("﻿", "") : raw;                 // a spreadsheet's BOM
                if (line.isBlank()) continue;
                var cells = split(line);
                if (number == 1 && isHeader(cells)) continue;
                out.add(new Line(number, cell(cells, 0), cell(cells, 1)));
            }
        }
        return out;
    }

    private List<Line> spreadsheet(byte[] bytes) throws IOException {
        var out = new ArrayList<Line>();
        var formatter = new DataFormatter();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                int number = row.getRowNum() + 1;
                var cells = List.of(text(formatter, row.getCell(0)), text(formatter, row.getCell(1)));
                if (cells.stream().allMatch(String::isBlank)) continue;
                if (number == 1 && isHeader(cells)) continue;
                out.add(new Line(number, cell(cells, 0), cell(cells, 1)));
            }
        }
        return out;
    }

    private static String text(DataFormatter formatter, Cell cell) { return cell == null ? "" : formatter.formatCellValue(cell).trim(); }

    /** `,`, `;` or a tab, whichever the first line actually uses; quotes around a cell are stripped. */
    private static List<String> split(String line) {
        char separator = line.indexOf('\t') >= 0 ? '\t' : line.indexOf(';') >= 0 && line.indexOf(',') < 0 ? ';' : ',';
        var out = new ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;
        for (char c : line.toCharArray()) {
            if (c == '"') { quoted = !quoted; continue; }
            if (c == separator && !quoted) { out.add(current.toString().trim()); current.setLength(0); continue; }
            current.append(c);
        }
        out.add(current.toString().trim());
        return out;
    }

    private static boolean isHeader(List<String> cells) {
        return !cells.isEmpty() && HEADER_NAMES.contains(cells.getFirst().trim().toLowerCase(Locale.ROOT));
    }

    private static String cell(List<String> cells, int index) {
        if (index >= cells.size()) return null;
        String value = cells.get(index) == null ? "" : cells.get(index).trim();
        return value.isEmpty() ? null : value;
    }
}
