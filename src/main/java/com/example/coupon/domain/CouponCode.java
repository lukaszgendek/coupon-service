package com.example.coupon.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** Coupon code normalised to upper case, so "wiosna" and "WIOSNA" are the same code. */
public record CouponCode(String value) {

	private static final Pattern ALLOWED = Pattern.compile("[A-Z0-9_-]{1,64}");

	public CouponCode {
		if (value == null || !ALLOWED.matcher(value).matches()) {
			throw new CouponException.InvalidInput("Coupon code must be 1-64 characters: letters, digits, '-' or '_'");
		}
	}

	public static CouponCode of(String raw) {
		if (raw == null) {
			throw new CouponException.InvalidInput("Coupon code is required");
		}
		return new CouponCode(raw.trim().toUpperCase(Locale.ROOT));
	}

}
