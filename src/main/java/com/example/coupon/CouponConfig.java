package com.example.coupon;

import com.example.coupon.application.CouponRepository;
import com.example.coupon.application.CouponService;
import com.example.coupon.application.GeoLocationPort;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root: wires the framework-free application layer to the adapters. */
@Configuration(proxyBeanMethods = false)
class CouponConfig {

	@Bean
	CouponService couponService(CouponRepository coupons, GeoLocationPort geo) {
		return new CouponService(coupons, geo);
	}

}
