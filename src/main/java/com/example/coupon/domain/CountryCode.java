package com.example.coupon.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** ISO 3166-1 alpha-2 country code, upper case. */
public record CountryCode(String value) {

	private static final Pattern ISO_ALPHA2 = Pattern.compile("[A-Z]{2}");

	public CountryCode {
		if (value == null || !ISO_ALPHA2.matcher(value).matches()) {
			throw new CouponException.InvalidInput("Country must be an ISO 3166-1 alpha-2 code, e.g. PL");
		}
	}

	public static CountryCode of(String raw) {
		if (raw == null) {
			throw new CouponException.InvalidInput("Country is required");
		}
		return new CountryCode(raw.trim().toUpperCase(Locale.ROOT));
	}

}
