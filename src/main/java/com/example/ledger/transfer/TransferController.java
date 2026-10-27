package com.example.ledger.transfer;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String CLIENT_ID_HEADER = "X-Client-Id";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * A replay of a successful request returns the original status (201) and body, plus
     * {@code Idempotent-Replayed: true}, rather than a conflict (spec 10).
     */
    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId,
            @RequestBody TransferRequest request) {
        TransferOutcome outcome = transferService.transfer(request, idempotencyKey, clientId);
        return ResponseEntity.status(outcome.httpStatus())
                .location(URI.create("/api/v1/transfers/" + outcome.response().transferId()))
                .header(REPLAYED_HEADER, Boolean.toString(outcome.replayed()))
                .body(outcome.response());
    }

    @GetMapping("/{transferId}")
    public TransferResponse get(@PathVariable UUID transferId) {
        return transferService.get(transferId);
    }
}
