package com.example.coupon.adapter.geo;

import com.example.coupon.domain.CountryCode;
import com.example.coupon.domain.CouponException;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Provider adapter for ipwho.is (or a compatible API): knows its URL and JSON, nothing else. */
class IpWhoIsLookup implements CountryLookup {

	private final RestClient client;

	IpWhoIsLookup(RestClient client) {
		this.client = client;
	}

	@Override
	public CountryCode lookup(String ip) {
		GeoResponse response;
		try {
			response = client.get().uri("/{ip}?fields=success,country_code", ip).retrieve().body(GeoResponse.class);
		}
		catch (RestClientException e) {
			throw new Unavailable("ipwho.is request failed", e);
		}
		if (response == null || !response.success() || response.countryCode() == null) {
			throw new UnknownAddress("ipwho.is returned no country for " + ip);
		}
		try {
			return CountryCode.of(response.countryCode());
		}
		catch (CouponException.InvalidInput e) {
			throw new Unavailable("ipwho.is returned an invalid country code", e);
		}
	}

	record GeoResponse(boolean success, @JsonProperty("country_code") String countryCode) {
	}

}
