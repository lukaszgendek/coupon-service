package com.example.coupon.application;

import com.example.coupon.domain.CountryCode;
import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponCode;
import com.example.coupon.domain.CouponException;

public class CouponService {

	private final CouponRepository coupons;

	private final GeoLocationPort geo;

	public CouponService(CouponRepository coupons, GeoLocationPort geo) {
		this.coupons = coupons;
		this.geo = geo;
	}

	public Coupon create(CouponCode code, CountryCode country, int maxUses) {
		return coupons.create(code, country, maxUses);
	}

	public Coupon get(CouponCode code) {
		return coupons.findByCode(code).orElseThrow(CouponException.NotFound::new);
	}

	/**
	 * Cheap checks run first, outside any transaction, so that rejected requests (unknown or exhausted coupon,
	 * wrong country) write nothing and do not hold a database connection while the geo lookup is in flight.
	 * The race for the last slots is decided by {@link CouponRepository#consume}.
	 */
	public void use(CouponCode code, String userId, String clientIp) {
		Coupon coupon = coupons.findByCode(code).orElseThrow(CouponException.NotFound::new);
		if (coupon.isExhausted()) {
			throw new CouponException.Exhausted();
		}
		CountryCode country = geo.countryOf(clientIp).orElseThrow(CouponException.CountryUnknown::new);
		if (!country.equals(coupon.country())) {
			throw new CouponException.CountryNotAllowed();
		}
		coupons.consume(coupon.id(), userId);
	}

}
