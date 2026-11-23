package com.example.coupon.application.port.out;

import java.util.Optional;

import com.example.coupon.domain.CountryCode;

public interface GeoLocationPort {

	/**
	 * Country of the given IP. Empty means the provider does not know the address.
	 *
	 * @throws com.example.coupon.domain.CouponException.ClientAddressNotPublic if the address is not a public one
	 * @throws com.example.coupon.domain.CouponException.GeoUnavailable if the lookup failed technically
	 */
	Optional<CountryCode> countryOf(String ip);

}
