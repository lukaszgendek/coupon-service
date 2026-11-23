package com.example.coupon.domain;

import java.util.regex.Pattern;

/**
 * Opaque identifier of the user, compared exactly (case is significant). Only a conservative character set is
 * accepted, which keeps control characters (PostgreSQL rejects NUL in text, newlines forge log lines) out of the system.
 */
public record UserId(String value) {

	private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

	public UserId {
		if (value == null || !ALLOWED.matcher(value).matches()) {
			throw new CouponException.InvalidInput("User id must be 1-64 characters: letters, digits, '.', '_', '@' or '-'");
		}
	}

	public static UserId of(String raw) {
		return new UserId(raw);
	}

}
