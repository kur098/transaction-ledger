package com.example.ledger.reconciliation;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.ledger.common.Money;

/**
 * Strict parser for the external transaction CSV (spec 9.1). Pure Java, no dependencies.
 *
 * <p>Format: UTF-8 (optional BOM), RFC 4180 quoting (double quotes, "" escapes, quoted fields may
 * span lines), CRLF or LF line endings. The header must contain every required column, in any
 * order; extra columns are ignored. Blank lines are skipped. Timestamps must be ISO-8601 with an
 * explicit offset (e.g. 2026-10-22T08:00:00Z) because local times are ambiguous.
 */
public final class CsvTransactionParser {

    public static final String COL_REFERENCE = "external_reference";
    public static final String COL_SOURCE = "source_account";
    public static final String COL_DESTINATION = "destination_account";
    public static final String COL_AMOUNT = "amount";
    public static final String COL_CURRENCY = "currency";
    public static final String COL_TIME = "transaction_time";
    public static final List<String> REQUIRED_COLUMNS =
            List.of(COL_REFERENCE, COL_SOURCE, COL_DESTINATION, COL_AMOUNT, COL_CURRENCY, COL_TIME);

    static final int MAX_FIELD_LENGTH = 64;
    static final int MAX_RAW_LINE_LENGTH = 500;
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final int maxRows;

    public CsvTransactionParser(int maxRows) {
        this.maxRows = maxRows;
    }

    public CsvParseResult parse(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return CsvParseResult.fileError("File is not valid UTF-8.");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }

        List<RawRecord> records = tokenize(text);
        if (records.isEmpty()) {
            return CsvParseResult.fileError("File is empty.");
        }

        RawRecord headerRecord = records.get(0);
        if (headerRecord.error() != null) {
            return CsvParseResult.fileError("Header row is malformed: " + headerRecord.error());
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < headerRecord.fields().size(); i++) {
            String name = headerRecord.fields().get(i).trim().toLowerCase(Locale.ROOT);
            if (columns.putIfAbsent(name, i) != null) {
                return CsvParseResult.fileError("Duplicate column in header: " + name);
            }
        }
        List<String> missing = REQUIRED_COLUMNS.stream().filter(c -> !columns.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            return CsvParseResult.fileError("Missing required column(s): " + String.join(", ", missing));
        }

        int dataRows = records.size() - 1;
        if (dataRows > maxRows) {
            return CsvParseResult.fileError("File has " + dataRows + " rows; the maximum is " + maxRows + ".");
        }

        List<ExternalRecord> valid = new ArrayList<>();
        List<InvalidRow> invalid = new ArrayList<>();
        int expectedFields = headerRecord.fields().size();
        for (RawRecord record : records.subList(1, records.size())) {
            parseRow(record, columns, expectedFields, valid, invalid);
        }
        return new CsvParseResult(null, valid, invalid);
    }

    private void parseRow(RawRecord record, Map<String, Integer> columns, int expectedFields,
                          List<ExternalRecord> valid, List<InvalidRow> invalid) {
        String raw = truncate(record.raw());
        if (record.error() != null) {
            invalid.add(new InvalidRow(record.lineNumber(), null, raw, List.of(record.error())));
            return;
        }
        if (record.fields().size() != expectedFields) {
            invalid.add(new InvalidRow(record.lineNumber(), null, raw, List.of(
                    "Expected " + expectedFields + " fields but found " + record.fields().size() + ".")));
            return;
        }

        Set<String> errors = new LinkedHashSet<>();
        String reference = text(record, columns, COL_REFERENCE, errors);
        String source = text(record, columns, COL_SOURCE, errors);
        String destination = text(record, columns, COL_DESTINATION, errors);
        BigDecimal amount = amount(field(record, columns, COL_AMOUNT), errors);
        String currency = currency(field(record, columns, COL_CURRENCY), errors);
        Instant time = timestamp(field(record, columns, COL_TIME), errors);

        if (source != null && source.equals(destination)) {
            errors.add("source_account and destination_account must differ.");
        }

        if (errors.isEmpty()) {
            valid.add(new ExternalRecord(record.lineNumber(), reference, source, destination, amount, currency, time));
        } else {
            invalid.add(new InvalidRow(record.lineNumber(), reference, raw, new ArrayList<>(errors)));
        }
    }

    private static String field(RawRecord record, Map<String, Integer> columns, String column) {
        return record.fields().get(columns.get(column)).trim();
    }

    private static String text(RawRecord record, Map<String, Integer> columns, String column, Set<String> errors) {
        String value = field(record, columns, column);
        if (value.isEmpty()) {
            errors.add(column + " is required.");
            return null;
        }
        if (value.length() > MAX_FIELD_LENGTH) {
            errors.add(column + " exceeds " + MAX_FIELD_LENGTH + " characters.");
            return null;
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            errors.add(column + " contains control characters.");
            return null;
        }
        return value;
    }

    private static BigDecimal amount(String value, Set<String> errors) {
        if (value.isEmpty()) {
            errors.add("amount is required.");
            return null;
        }
        if (!PLAIN_DECIMAL.matcher(value).matches()) {
            errors.add("amount '" + abbreviate(value) + "' is not a plain decimal number.");
            return null;
        }
        BigDecimal amount = new BigDecimal(value);
        if (amount.signum() <= 0) {
            errors.add("amount must be greater than zero.");
            return null;
        }
        if (!Money.isRepresentable(amount)) {
            errors.add("amount must have at most " + Money.SCALE + " decimal places.");
            return null;
        }
        return Money.normalize(amount);
    }

    private static String currency(String value, Set<String> errors) {
        String upper = value.toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(upper).matches()) {
            errors.add("currency '" + abbreviate(value) + "' is not a 3-letter ISO 4217 code.");
            return null;
        }
        return upper;
    }

    private static Instant timestamp(String value, Set<String> errors) {
        if (value.isEmpty()) {
            errors.add("transaction_time is required.");
            return null;
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            errors.add("transaction_time '" + abbreviate(value)
                    + "' is not an ISO-8601 timestamp with offset (e.g. 2026-10-22T08:00:00Z).");
            return null;
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 40 ? value : value.substring(0, 40) + "...";
    }

    private static String truncate(String raw) {
        return raw.length() <= MAX_RAW_LINE_LENGTH ? raw : raw.substring(0, MAX_RAW_LINE_LENGTH);
    }

    // ---------------------------------------------------------------------------------------
    // RFC 4180 tokenizer
    // ---------------------------------------------------------------------------------------

    record RawRecord(int lineNumber, List<String> fields, String raw, String error) {
    }

    static List<RawRecord> tokenize(String text) {
        List<RawRecord> records = new ArrayList<>();
        int length = text.length();
        int pos = 0;
        int line = 1;

        while (pos < length) {
            int startLine = line;
            int startPos = pos;
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean inQuotes = false;
            boolean fieldWasQuoted = false;
            String error = null;
            boolean endOfRecord = false;

            while (pos < length && !endOfRecord) {
                char c = text.charAt(pos);
                if (inQuotes) {
                    if (c == '"') {
                        if (pos + 1 < length && text.charAt(pos + 1) == '"') {
                            field.append('"');
                            pos += 2;
                        } else {
                            inQuotes = false;
                            pos++;
                        }
                    } else {
                        if (c == '\n') {
                            line++;
                        }
                        field.append(c);
                        pos++;
                    }
                } else if (c == '"') {
                    if (field.length() == 0 && !fieldWasQuoted) {
                        inQuotes = true;
                        fieldWasQuoted = true;
                    } else if (error == null) {
                        error = "Unexpected quote character inside field " + (fields.size() + 1) + ".";
                    }
                    pos++;
                } else if (c == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                    fieldWasQuoted = false;
                    pos++;
                } else if (c == '\r' || c == '\n') {
                    if (c == '\r' && pos + 1 < length && text.charAt(pos + 1) == '\n') {
                        pos++;
                    }
                    pos++;
                    line++;
                    endOfRecord = true;
                } else {
                    if (fieldWasQuoted && error == null) {
                        error = "Characters after closing quote in field " + (fields.size() + 1) + ".";
                    }
                    field.append(c);
                    pos++;
                }
            }
            if (inQuotes) {
                error = "Unterminated quoted field.";
            }
            fields.add(field.toString());

            String raw = text.substring(startPos, pos).stripTrailing();
            boolean blank = fields.size() == 1 && fields.get(0).isBlank() && !fieldWasQuoted;
            if (!blank) {
                records.add(new RawRecord(startLine, List.copyOf(fields), raw, error));
            }
        }
        return records;
    }
}
