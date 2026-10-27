package com.example.ledger.transfer.exception;

import com.example.ledger.common.ValidationException;

/** The request violates a field rule; nothing was claimed, locked or written. Maps to 400. */
public class InvalidTransferException extends ValidationException {

    public InvalidTransferException(String code, String message) {
        super(code, message);
    }
}
