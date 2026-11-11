package com.payrail.payment.common;

import java.util.List;

/** The single error shape for every error response in the API. */
public record ApiErrorResponse(ApiError error) {

    public record ApiError(String code, String message, List<FieldViolation> fieldErrors) {
    }

    public record FieldViolation(String field, String message) {
    }

    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(new ApiError(code, message, null));
    }

    public static ApiErrorResponse withFieldErrors(String code, String message, List<FieldViolation> fieldErrors) {
        return new ApiErrorResponse(new ApiError(code, message, fieldErrors));
    }
}