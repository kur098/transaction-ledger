package com.example.ledger.reconciliation;

import com.example.ledger.common.LedgerProperties;
import com.example.ledger.idempotency.RequestFingerprint;

import org.springframework.stereotype.Service;

/** File ingestion: identifies the input by SHA-256 and validates/parses it (spec 9.1, 9.6). */
@Service
public class CsvImportService {

    private final CsvTransactionParser parser;

    public CsvImportService(LedgerProperties properties) {
        this.parser = new CsvTransactionParser(properties.reconciliation().maxRows());
    }

    public String hash(byte[] content) {
        return RequestFingerprint.sha256Hex(content);
    }

    public CsvParseResult parse(byte[] content) {
        return parser.parse(content);
    }
}
