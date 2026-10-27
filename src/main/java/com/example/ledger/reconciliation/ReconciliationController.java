package com.example.ledger.reconciliation;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.example.ledger.common.LedgerProperties;
import com.example.ledger.common.PageRequestParams;
import com.example.ledger.common.PageResponse;
import com.example.ledger.common.ValidationException;
import com.example.ledger.reconciliation.dto.ReconciliationItemResponse;
import com.example.ledger.reconciliation.dto.ReconciliationRunResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/reconciliations")
public class ReconciliationController {

    public static final String REUSED_HEADER = "Reconciliation-Reused";

    private final ReconciliationService service;
    private final LedgerProperties properties;

    public ReconciliationController(ReconciliationService service, LedgerProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * Uploads an external transaction file (multipart field {@code file}) and reconciles it.
     * Returns 201 with the new run (its status may be COMPLETED or REJECTED), or 200 with the
     * existing run when the identical file was already processed and {@code rerun} is false.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReconciliationRunResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "PARTIAL") String mode,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Integer toleranceSeconds,
            @RequestParam(defaultValue = "false") boolean rerun) throws IOException {

        ReconciliationOptions options = new ReconciliationOptions(
                parseMode(mode),
                from,
                to,
                currency == null || currency.isBlank() ? null : currency.trim().toUpperCase(Locale.ROOT),
                toleranceSeconds != null ? toleranceSeconds : properties.reconciliation().defaultTimeToleranceSeconds(),
                rerun);

        ReconciliationService.Outcome outcome = service.reconcile(file.getOriginalFilename(), file.getBytes(), options);
        ReconciliationRunResponse body = ReconciliationRunResponse.from(outcome.run());
        return ResponseEntity.status(outcome.reused() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/v1/reconciliations/" + body.runId()))
                .header(REUSED_HEADER, Boolean.toString(outcome.reused()))
                .body(body);
    }

    @GetMapping("/{runId}")
    public ReconciliationRunResponse get(@PathVariable UUID runId) {
        return ReconciliationRunResponse.from(service.getRun(runId));
    }

    /** Findings for a run; optionally filtered by {@code type}, e.g. ?type=AMOUNT_MISMATCH. */
    @GetMapping("/{runId}/items")
    public PageResponse<ReconciliationItemResponse> items(@PathVariable UUID runId,
                                                          @RequestParam(required = false) ReconciliationResultType type,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "100") int size) {
        return service.getItems(runId, type, PageRequestParams.of(page, size));
    }

    private static ReconciliationRun.Mode parseMode(String mode) {
        try {
            return ReconciliationRun.Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("INVALID_MODE", "mode must be STRICT or PARTIAL.");
        }
    }
}
