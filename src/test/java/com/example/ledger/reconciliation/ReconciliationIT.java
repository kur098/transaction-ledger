package com.example.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.transfer.TransferRequest;
import com.example.ledger.transfer.TransferService;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** End-to-end reconciliation (spec 9, acceptance criteria 14) against PostgreSQL. */
class ReconciliationIT extends AbstractIntegrationTest {

    private static final String HEADER =
            "external_reference,source_account,destination_account,amount,currency,transaction_time\n";

    @Autowired
    MockMvc mvc;

    @Autowired
    TransferService transferService;

    @Autowired
    ReconciliationService reconciliationService;

    @SpyBean
    ReconciliationRepository reconciliationRepository;

    private UUID acc1;
    private UUID acc2;
    private UUID acc3;
    private UUID acc4;
    private UUID acc5;
    private UUID acc6;
    private String now;

    @BeforeEach
    void ledgerWithReferencedTransfers() {
        acc1 = createAccount("ACC-001", "1000.00");
        acc2 = createAccount("ACC-002", "0.00");
        acc3 = createAccount("ACC-003", "1000.00");
        acc4 = createAccount("ACC-004", "0.00");
        acc5 = createAccount("ACC-005", "1000.00");
        acc6 = createAccount("ACC-006", "0.00");

        transfer(acc1, acc2, "150.00", "EXT-1001", "k1");   // matched
        transfer(acc3, acc4, "75.00", "EXT-1002", "k2");    // amount mismatch (file says 80.00)
        transfer(acc5, acc6, "40.00", "EXT-1003", "k3");    // file repeats this row
        transfer(acc1, acc2, "5.00", "EXT-2000", "k4");     // not in file -> missing external
        transfer(acc3, acc4, "9.00", "EXT-3000", "k5");     // processed twice internally
        transfer(acc3, acc4, "9.00", "EXT-3000", "k6");     //   (client retried with a new key)

        now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    @AfterEach
    void resetSpy() {
        reset(reconciliationRepository);
    }

    private void transfer(UUID src, UUID dst, String amount, String reference, String key) {
        transferService.transfer(new TransferRequest(src, dst, new BigDecimal(amount), "USD", reference), key, null);
    }

    private String mixedFile() {
        return HEADER
                + "EXT-1001,ACC-001,ACC-002,150.00,USD," + now + "\n"   // row 2 MATCHED
                + "EXT-1002,ACC-003,ACC-004,80.00,USD," + now + "\n"    // row 3 AMOUNT_MISMATCH
                + "EXT-1003,ACC-005,ACC-006,40.00,USD," + now + "\n"    // row 4 MATCHED
                + "EXT-1003,ACC-005,ACC-006,40.00,USD," + now + "\n"    // row 5 DUPLICATE_EXTERNAL
                + "EXT-9999,ACC-001,ACC-002,12.00,USD," + now + "\n"    // row 6 MISSING_INTERNAL
                + "EXT-BAD,ACC-001,ACC-002,abc,USD," + now + "\n"       // row 7 INVALID_EXTERNAL_RECORD
                + "EXT-3000,ACC-003,ACC-004,9.00,USD," + now + "\n";    // row 8 DUPLICATE_INTERNAL
    }

    private MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "statement.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void classifiesEveryDiscrepancyTypeAndPersistsAnAuditableRun() throws Exception {
        long transfersBefore = count("transfers");
        long entriesBefore = count("ledger_entries");
        BigDecimal acc1Before = balanceOf(acc1);

        MvcResult result = mvc.perform(multipart("/api/v1/reconciliations").file(file(mixedFile())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Reconciliation-Reused", "false"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.fileName").value("statement.csv"))
                .andExpect(jsonPath("$.fileHash").isString())
                .andExpect(jsonPath("$.totalExternalRecords").value(7))
                .andExpect(jsonPath("$.validRecords").value(6))
                .andExpect(jsonPath("$.matched").value(2))
                .andExpect(jsonPath("$.mismatched").value(1))
                .andExpect(jsonPath("$.missingInternal").value(1))
                .andExpect(jsonPath("$.missingExternal").value(1))
                .andExpect(jsonPath("$.duplicateExternal").value(1))
                .andExpect(jsonPath("$.duplicateInternal").value(1))
                .andExpect(jsonPath("$.invalidRecords").value(1))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.completedAt").exists())
                .andReturn();
        String runId = JsonPath.read(result.getResponse().getContentAsString(), "$.runId");

        // Row-level findings are retrievable and investigable.
        mvc.perform(get("/api/v1/reconciliations/{id}/items", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(8))           // 7 rows + 1 missing external
                .andExpect(jsonPath("$.content[0].rowNumber").value(2))
                .andExpect(jsonPath("$.content[0].resultType").value("MATCHED"))
                .andExpect(jsonPath("$.content[1].resultType").value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.content[1].details.external.amount").value("80.00"))
                .andExpect(jsonPath("$.content[1].details.internal.amount").value("75.00"))
                .andExpect(jsonPath("$.content[3].resultType").value("DUPLICATE_EXTERNAL"))
                .andExpect(jsonPath("$.content[3].details.firstRowNumber").value(4))
                .andExpect(jsonPath("$.content[4].resultType").value("MISSING_INTERNAL"))
                .andExpect(jsonPath("$.content[5].resultType").value("INVALID_EXTERNAL_RECORD"))
                .andExpect(jsonPath("$.content[5].details.errors[0]").isString())
                .andExpect(jsonPath("$.content[6].resultType").value("DUPLICATE_INTERNAL"))
                .andExpect(jsonPath("$.content[6].details.transferIds.length()").value(2))
                .andExpect(jsonPath("$.content[7].resultType").value("MISSING_EXTERNAL"))
                .andExpect(jsonPath("$.content[7].rowNumber").doesNotExist());

        mvc.perform(get("/api/v1/reconciliations/{id}/items", runId).param("type", "MISSING_EXTERNAL"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].externalReference").value("EXT-2000"));

        mvc.perform(get("/api/v1/reconciliations/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId));

        // Valid rows were staged; the ledger was not touched.
        assertThat(count("external_transactions")).isEqualTo(6);
        assertThat(count("transfers")).isEqualTo(transfersBefore);
        assertThat(count("ledger_entries")).isEqualTo(entriesBefore);
        assertThat(balanceOf(acc1)).isEqualByComparingTo(acc1Before);
    }

    @Test
    void reimportingTheIdenticalFileReusesTheRunWithoutDuplicatingRecords() throws Exception {
        String content = mixedFile();
        MvcResult first = mvc.perform(multipart("/api/v1/reconciliations").file(file(content)))
                .andExpect(status().isCreated()).andReturn();
        String runId = JsonPath.read(first.getResponse().getContentAsString(), "$.runId");
        long staged = count("external_transactions");
        long items = count("reconciliation_items");

        mvc.perform(multipart("/api/v1/reconciliations").file(file(content)))
                .andExpect(status().isOk())
                .andExpect(header().string("Reconciliation-Reused", "true"))
                .andExpect(jsonPath("$.runId").value(runId));

        assertThat(count("reconciliation_runs")).isEqualTo(1);
        assertThat(count("external_transactions")).isEqualTo(staged);
        assertThat(count("reconciliation_items")).isEqualTo(items);

        // An explicit rerun creates a new, independent run.
        mvc.perform(multipart("/api/v1/reconciliations").file(file(content)).param("rerun", "true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value(org.hamcrest.Matchers.not(runId)));
        assertThat(count("reconciliation_runs")).isEqualTo(2);
    }

    @Test
    void strictModeRejectsTheWholeFileWhenAnyRowIsInvalid() throws Exception {
        mvc.perform(multipart("/api/v1/reconciliations").file(file(mixedFile())).param("mode", "STRICT"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.invalidRecords").value(1))
                .andExpect(jsonPath("$.matched").value(0))
                .andExpect(jsonPath("$.failureReason").value(org.hamcrest.Matchers.containsString("Strict mode")));
        assertThat(count("external_transactions")).isZero();
        assertThat(count("reconciliation_items")).isEqualTo(1);
    }

    @Test
    void fileWithBadHeaderIsRejectedAndRecorded() throws Exception {
        mvc.perform(multipart("/api/v1/reconciliations").file(file("ref,amount\nX,1.00\n")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.failureReason").value(org.hamcrest.Matchers.containsString("Missing required column")));
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM operation_errors WHERE operation = 'RECONCILIATION'", Integer.class)).isEqualTo(1);
    }

    @Test
    void timeToleranceAndScopeParametersApply() throws Exception {
        String later = Instant.parse(now).plus(2, ChronoUnit.HOURS).toString();
        String content = HEADER + "EXT-1001,ACC-001,ACC-002,150.00,USD," + later + "\n";

        mvc.perform(multipart("/api/v1/reconciliations").file(file(content)).param("toleranceSeconds", "60")
                        .param("from", Instant.parse(now).minus(1, ChronoUnit.DAYS).toString())
                        .param("to", Instant.parse(now).plus(1, ChronoUnit.DAYS).toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mismatched").value(1))
                .andExpect(jsonPath("$.missingExternal").value(5)) // every other transfer is in the explicit scope
                .andExpect(jsonPath("$.timeToleranceSeconds").value(60));

        mvc.perform(multipart("/api/v1/reconciliations").file(file(content)).param("rerun", "true")
                        .param("toleranceSeconds", "86400")
                        .param("from", Instant.parse(now).plus(1, ChronoUnit.DAYS).toString())
                        .param("to", Instant.parse(now).plus(2, ChronoUnit.DAYS).toString()))
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.missingExternal").value(0)); // nothing internal in that window
    }

    @Test
    void invalidParametersReturn400() throws Exception {
        mvc.perform(multipart("/api/v1/reconciliations").file(file(mixedFile())).param("mode", "LENIENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MODE"));
        mvc.perform(multipart("/api/v1/reconciliations").file(file(mixedFile())).param("from", now))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCOPE"));
        mvc.perform(get("/api/v1/reconciliations/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void failedImportRollsBackStagedRowsAndCanBeRetriedSafely() {
        byte[] content = mixedFile().getBytes(StandardCharsets.UTF_8);
        ReconciliationOptions options = new ReconciliationOptions(ReconciliationRun.Mode.PARTIAL,
                null, null, null, 3600, false);
        doThrow(new IllegalStateException("simulated storage failure"))
                .when(reconciliationRepository).insertItems(anyList());

        assertThatThrownBy(() -> reconciliationService.reconcile("statement.csv", content, options))
                .hasMessageContaining("simulated storage failure");

        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT status FROM reconciliation_runs", String.class)).isEqualTo("FAILED");
        assertThat(count("external_transactions")).as("staged rows rolled back").isZero();
        assertThat(count("reconciliation_items")).isZero();

        reset(reconciliationRepository);
        ReconciliationService.Outcome retry = reconciliationService.reconcile("statement.csv", content, options);

        assertThat(retry.reused()).as("FAILED runs are never reused").isFalse();
        assertThat(retry.run().status()).isEqualTo(ReconciliationRun.Status.COMPLETED);
        assertThat(count("external_transactions")).isEqualTo(6);
        assertThat(count("reconciliation_runs")).isEqualTo(2);
    }
}
