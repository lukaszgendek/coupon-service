package com.example.coupon;

import java.util.Optional;

public interface GeoLocationPort {

	/** Country of the given IP, or empty when it cannot be determined. */
	Optional<CountryCode> countryOf(String ip);

}
