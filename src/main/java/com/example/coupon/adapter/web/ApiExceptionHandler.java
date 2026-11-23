package com.example.coupon.adapter.web;

import com.example.coupon.domain.CouponException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps failures to RFC 9457 problem details; the machine-readable reason is in the "error" property. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(CouponException.class)
	ProblemDetail handleCoupon(CouponException e) {
		return switch (e) {
			case CouponException.InvalidInput ex -> problem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", ex);
			case CouponException.NotFound ex -> problem(HttpStatus.NOT_FOUND, "COUPON_NOT_FOUND", ex);
			case CouponException.CountryNotAllowed ex -> problem(HttpStatus.FORBIDDEN, "COUNTRY_NOT_ALLOWED", ex);
			case CouponException.Exhausted ex -> problem(HttpStatus.CONFLICT, "COUPON_EXHAUSTED", ex);
			case CouponException.AlreadyUsed ex -> problem(HttpStatus.CONFLICT, "COUPON_ALREADY_USED", ex);
			case CouponException.DuplicateCode ex -> problem(HttpStatus.CONFLICT, "COUPON_CODE_TAKEN", ex);
			case CouponException.CountryUnknown ex -> problem(HttpStatus.SERVICE_UNAVAILABLE, "COUNTRY_UNKNOWN", ex);
		};
	}

	/** Includes the connection pool timeout under overload: shed load instead of queueing forever. */
	@ExceptionHandler({ DataAccessResourceFailureException.class, CannotCreateTransactionException.class })
	ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception e) {
		log.warn("Database unavailable or overloaded", e);
		ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"Service temporarily overloaded, retry later");
		body.setProperty("error", "SERVICE_OVERLOADED");
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(body);
	}

	private static ProblemDetail problem(HttpStatus status, String error, CouponException e) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(status, e.getMessage());
		body.setProperty("error", error);
		return body;
	}

}
