package com.example.coupon.application;

import java.util.Optional;

import com.example.coupon.domain.CountryCode;

public interface GeoLocationPort {

	/** Country of the given IP, or empty when it cannot be determined. */
	Optional<CountryCode> countryOf(String ip);

}
