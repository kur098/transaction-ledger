package com.example.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.example.ledger.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** HTTP contract (spec 6.1, 6.2, 10) exercised end to end through MockMvc and PostgreSQL. */
class TransferApiIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    private String body(UUID src, UUID dst, String amount) {
        return """
                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":"%s","currency":"USD"}
                """.formatted(src, dst, amount);
    }

    @Test
    void createTransferReturns201WithExactStringAmount() throws Exception {
        UUID src = createAccount("ACC-001", "500.00");
        UUID dst = createAccount("ACC-002", "0.00");

        mvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "api-key-1")
                        .content(body(src, dst, "150.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(header().exists("Location"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value("150.00"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.sourceAccountId").value(src.toString()))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void replayReturnsTheOriginalResultNotAConflict() throws Exception {
        UUID src = createAccount("ACC-001", "500.00");
        UUID dst = createAccount("ACC-002", "0.00");

        MvcResult first = mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "api-key-2").content(body(src, dst, "10.00")))
                .andExpect(status().isCreated()).andReturn();
        MvcResult second = mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "api-key-2").content(body(src, dst, "10.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(count("transfers")).isEqualTo(1);
    }

    @Test
    void keyReuseWithDifferentBodyReturns409() throws Exception {
        UUID src = createAccount("ACC-001", "500.00");
        UUID dst = createAccount("ACC-002", "0.00");
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "api-key-3").content(body(src, dst, "10.00")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "api-key-3").content(body(src, dst, "11.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void insufficientFundsReturns422WithErrorContract() throws Exception {
        UUID src = createAccount("ACC-001", "10.00");
        UUID dst = createAccount("ACC-002", "0.00");

        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "api-key-4")
                        .header("X-Request-Id", "req-12345")
                        .content(body(src, dst, "10.01")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message").value("The source account has insufficient funds."))
                .andExpect(jsonPath("$.requestId").value("req-12345"));
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), UUID.randomUUID(), "1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void malformedAmountOrJsonReturns400() throws Exception {
        UUID src = createAccount("ACC-001", "10.00");
        UUID dst = createAccount("ACC-002", "0.00");

        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "api-key-5").content(body(src, dst, "ten")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "api-key-6").content(body(src, dst, "1.001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));

        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "api-key-7").content("{not json"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "api-key-8")
                        .content("{\"sourceAccountId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());

        assertThat(count("transfers")).isZero();
    }

    @Test
    void unknownAccountReturns404AndUnknownTransferReturns404() throws Exception {
        UUID src = createAccount("ACC-001", "10.00");
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "api-key-9").content(body(src, UUID.randomUUID(), "1.00")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        mvc.perform(get("/api/v1/transfers/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void errorResponsesNeverLeakInternals() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", not(containsString("Exception"))))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void accountEndpointsAndPaginatedLedger() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"ACC-100\",\"currency\":\"USD\",\"openingBalance\":\"200.00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value("200.00"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String srcId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.accountId");
        UUID src = UUID.fromString(srcId);
        UUID dst = createAccount("ACC-200", "0.00");

        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"ACC-100\",\"currency\":\"USD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NUMBER_TAKEN"));

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", "ledger-" + i).content(body(src, dst, "10.00")))
                    .andExpect(status().isCreated());
        }

        mvc.perform(get("/api/v1/accounts/{id}", src))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("170.00"))
                .andExpect(jsonPath("$.openingBalance").value("200.00"));

        mvc.perform(get("/api/v1/accounts/{id}/ledger", src).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].entryType").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].counterpartyAccountId").value(dst.toString()));

        mvc.perform(patch("/api/v1/accounts/{id}/status", src).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BALANCE_NOT_ZERO"));

        mvc.perform(patch("/api/v1/accounts/{id}/status", src).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FROZEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mvc.perform(get("/api/v1/ledger/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.totalDebits").value("30.00"));
    }

    @Test
    void invalidAccountCreationReturns400() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"ACC-1\",\"currency\":\"JPY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"ACC-1\",\"currency\":\"USD\",\"openingBalance\":\"-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
