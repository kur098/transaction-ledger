package com.example.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class CsvTransactionParserTest {

    private static final String HEADER =
            "external_reference,source_account,destination_account,amount,currency,transaction_time\n";

    private final CsvTransactionParser parser = new CsvTransactionParser(1000);

    private CsvParseResult parse(String csv) {
        return parser.parse(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesTheSpecExampleFile() {
        CsvParseResult result = parse(HEADER
                + "EXT-1001,ACC-001,ACC-002,150.00,USD,2026-10-22T08:00:00Z\n"
                + "EXT-1002,ACC-003,ACC-004,75.00,USD,2026-10-22T08:05:00Z\n"
                + "EXT-1003,ACC-005,ACC-006,40.00,USD,2026-10-22T08:10:00Z\n");

        assertThat(result.hasFileError()).isFalse();
        assertThat(result.invalidRows()).isEmpty();
        assertThat(result.validRecords()).hasSize(3);
        ExternalRecord first = result.validRecords().get(0);
        assertThat(first.rowNumber()).isEqualTo(2);
        assertThat(first.externalReference()).isEqualTo("EXT-1001");
        assertThat(first.sourceAccount()).isEqualTo("ACC-001");
        assertThat(first.destinationAccount()).isEqualTo("ACC-002");
        assertThat(first.amount()).isEqualByComparingTo("150.00");
        assertThat(first.currency()).isEqualTo("USD");
        assertThat(first.transactionTime()).isEqualTo(Instant.parse("2026-10-22T08:00:00Z"));
    }

    @Test
    void acceptsBomCrlfQuotedFieldsReorderedAndExtraColumns() {
        String csv = "﻿currency,amount,transaction_time,external_reference,destination_account,source_account,memo\r\n"
                + "usd,\"1250.5\",2026-10-22T17:00:00+09:00,\"EXT-9\",ACC-2,ACC-1,\"rent, \"\"October\"\"\"\r\n";
        CsvParseResult result = parse(csv);

        assertThat(result.hasFileError()).isFalse();
        assertThat(result.invalidRows()).isEmpty();
        ExternalRecord row = result.validRecords().get(0);
        assertThat(row.currency()).isEqualTo("USD");
        assertThat(row.amount()).isEqualByComparingTo("1250.50");
        assertThat(row.transactionTime()).isEqualTo(Instant.parse("2026-10-22T08:00:00Z"));
        assertThat(row.sourceAccount()).isEqualTo("ACC-1");
    }

    @Test
    void blankLinesAreSkippedButRowNumbersStayPhysical() {
        CsvParseResult result = parse(HEADER + "\n"
                + "EXT-1,A,B,1.00,USD,2026-10-22T08:00:00Z\n\n"
                + "EXT-2,A,B,2.00,USD,2026-10-22T08:00:00Z\n");
        assertThat(result.validRecords()).extracting(ExternalRecord::rowNumber).containsExactly(3, 5);
    }

    @Test
    void missingHeaderColumnsRejectTheFile() {
        CsvParseResult result = parse("external_reference,source_account,amount\nEXT-1,A,1.00\n");
        assertThat(result.hasFileError()).isTrue();
        assertThat(result.fileError()).contains("destination_account", "currency", "transaction_time");
        assertThat(result.totalRows()).isZero();
    }

    @Test
    void emptyFileAndInvalidUtf8AreFileErrors() {
        assertThat(parse("").fileError()).isEqualTo("File is empty.");
        assertThat(parser.parse(new byte[]{(byte) 0xC3, (byte) 0x28}).fileError()).contains("UTF-8");
    }

    @Test
    void duplicateHeaderColumnIsAFileError() {
        assertThat(parse("amount,amount\n").fileError()).contains("Duplicate column");
    }

    @Test
    void tooManyRowsIsAFileError() {
        CsvTransactionParser small = new CsvTransactionParser(1);
        CsvParseResult result = small.parse((HEADER
                + "EXT-1,A,B,1.00,USD,2026-10-22T08:00:00Z\n"
                + "EXT-2,A,B,1.00,USD,2026-10-22T08:00:00Z\n").getBytes(StandardCharsets.UTF_8));
        assertThat(result.fileError()).contains("maximum is 1");
    }

    @Test
    void invalidRowsAreCapturedWithRowNumbersAndAllErrors() {
        CsvParseResult result = parse(HEADER
                + "EXT-1,ACC-1,ACC-2,150.00,USD,2026-10-22T08:00:00Z\n"   // row 2 valid
                + ",ACC-1,ACC-2,abc,US,yesterday\n"                       // row 3: four errors
                + "EXT-3,ACC-1,ACC-2,1.005,USD,2026-10-22T08:00:00Z\n"    // row 4: too many decimals
                + "EXT-4,ACC-1,ACC-2,-5.00,USD,2026-10-22T08:00:00Z\n"    // row 5: negative
                + "EXT-5,ACC-1,ACC-2,5.00,USD,2026-10-22T08:00:00\n"      // row 6: no offset
                + "EXT-6,ACC-1,ACC-2,5.00\n"                              // row 7: wrong field count
                + "EXT-7,ACC-1,ACC-1,5.00,USD,2026-10-22T08:00:00Z\n"     // row 8: same account
                + "EXT-8,ACC-1,ACC-2,1E3,USD,2026-10-22T08:00:00Z\n");    // row 9: exponent

        assertThat(result.validRecords()).hasSize(1);
        assertThat(result.invalidRows()).extracting(InvalidRow::rowNumber).containsExactly(3, 4, 5, 6, 7, 8, 9);

        InvalidRow row3 = result.invalidRows().get(0);
        assertThat(row3.errors()).hasSize(4);
        assertThat(String.join(" ", row3.errors()))
                .contains("external_reference is required", "amount", "currency", "transaction_time");
        assertThat(row3.rawLine()).isEqualTo(",ACC-1,ACC-2,abc,US,yesterday");

        assertThat(result.invalidRows().get(1).externalReference()).isEqualTo("EXT-3");
        assertThat(result.invalidRows().get(4).errors().get(0)).contains("Expected 6 fields");
        assertThat(result.totalRows()).isEqualTo(8);
    }

    @Test
    void unterminatedQuoteIsAnInvalidRow() {
        CsvParseResult result = parse(HEADER + "EXT-1,ACC-1,ACC-2,\"5.00,USD,2026-10-22T08:00:00Z\n");
        assertThat(result.invalidRows()).hasSize(1);
        assertThat(result.invalidRows().get(0).errors()).anyMatch(e -> e.contains("Unterminated"));
    }

    @Test
    void tokenizerHandlesEscapedQuotesAndEmbeddedNewlines() {
        var records = CsvTransactionParser.tokenize("a,\"b \"\"x\"\"\nline2\",c\nd,e,f");
        assertThat(records).hasSize(2);
        assertThat(records.get(0).fields()).containsExactly("a", "b \"x\"\nline2", "c");
        assertThat(records.get(1).lineNumber()).isEqualTo(3);
    }
}
