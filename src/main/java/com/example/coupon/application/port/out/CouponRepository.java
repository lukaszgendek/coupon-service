package com.example.coupon.application.port.out;

import java.util.Optional;

import com.example.coupon.domain.CountryCode;
import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponCode;
import com.example.coupon.domain.UserId;

public interface CouponRepository {

	/** @throws CouponException.DuplicateCode if the code is already taken */
	Coupon create(CouponCode code, CountryCode country, int maxUses);

	Optional<Coupon> findByCode(CouponCode code);

	/**
	 * Atomically records the usage and takes one slot, all or nothing.
	 *
	 * @throws CouponException.AlreadyUsed if the user already used the coupon
	 * @throws CouponException.Exhausted if the last slot was taken by someone else
	 */
	void consume(long couponId, UserId userId);

}
