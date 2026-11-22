package com.example.coupon.domain;

import java.time.Instant;

public record Coupon(long id, CouponCode code, CountryCode country, int maxUses, int usedCount, Instant createdAt) {

	public boolean isExhausted() {
		return usedCount >= maxUses;
	}

}
