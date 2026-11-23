package com.example.coupon.application.port.in;

import com.example.coupon.domain.CountryCode;
import com.example.coupon.domain.Coupon;
import com.example.coupon.domain.CouponCode;
import com.example.coupon.domain.UserId;

/** What the outside world can ask of the application. Implemented by the application, called by driving adapters. */
public interface CouponUseCases {

	Coupon create(CouponCode code, CountryCode country, int maxUses);

	Coupon get(CouponCode code);

	void use(CouponCode code, UserId userId, String clientIp);

}
