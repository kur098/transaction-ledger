package com.example.ledger.ledger;

import java.util.List;
import java.util.UUID;

import com.example.ledger.account.AccountRepository;
import com.example.ledger.common.NotFoundException;
import com.example.ledger.common.PageRequestParams;
import com.example.ledger.common.PageResponse;
import com.example.ledger.ledger.dto.LedgerEntryResponse;
import com.example.ledger.ledger.dto.LedgerIntegrityReport;
import com.example.ledger.ledger.dto.LedgerIntegrityReport.AccountDrift;
import com.example.ledger.ledger.dto.LedgerIntegrityReport.DebitCreditTotals;
import com.example.ledger.ledger.dto.LedgerIntegrityReport.UnbalancedTransfer;
import com.example.ledger.transfer.Transfer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The controlled posting procedure: the only code path that writes ledger entries. It always
 * writes both sides of a transfer together; the deferred database trigger is the second line
 * of defence if this code is ever bypassed or changed incorrectly.
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository entries;
    private final AccountRepository accounts;

    public LedgerService(LedgerEntryRepository entries, AccountRepository accounts) {
        this.entries = entries;
        this.accounts = accounts;
    }

    /** Must run inside the caller's transfer transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerEntry> postTransfer(Transfer transfer) {
        List<LedgerEntry> posting = List.of(LedgerEntry.debit(transfer), LedgerEntry.credit(transfer));
        entries.insertAll(posting);
        return posting;
    }

    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> accountLedger(UUID accountId, PageRequestParams page) {
        if (accounts.findById(accountId).isEmpty()) {
            throw NotFoundException.account(accountId);
        }
        List<LedgerEntryResponse> content = entries.findByAccount(accountId, page.size(), page.offset());
        long total = entries.countByAccount(accountId);
        return PageResponse.of(content, page.page(), page.size(), total);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> entriesForTransfer(UUID transferId) {
        return entries.findByTransferId(transferId);
    }

    /**
     * Full audit of the ledger invariants. Runs in a REPEATABLE READ read-only transaction so that
     * every query sees the same snapshot even while transfers are committing concurrently.
     */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public LedgerIntegrityReport verifyIntegrity() {
        DebitCreditTotals totals = entries.totalDebitsAndCredits();
        List<UnbalancedTransfer> unbalanced = entries.findUnbalancedTransfers();
        List<AccountDrift> drift = entries.findAccountsWhoseBalanceDisagreesWithLedger();
        boolean consistent = totals.debits().compareTo(totals.credits()) == 0
                && unbalanced.isEmpty()
                && drift.isEmpty();
        return new LedgerIntegrityReport(consistent, totals.debits(), totals.credits(), unbalanced, drift);
    }
}
