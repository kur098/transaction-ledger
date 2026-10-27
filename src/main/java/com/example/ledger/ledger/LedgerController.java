package com.example.ledger.ledger;

import com.example.ledger.ledger.dto.LedgerIntegrityReport;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational endpoint that audits the whole ledger. In a real deployment this would sit behind
 * administrator authorization (authentication is out of scope per spec section 1).
 */
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/integrity")
    public LedgerIntegrityReport integrity() {
        return ledgerService.verifyIntegrity();
    }
}
