package com.example.coupon.adapter.geo;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("coupon.geo")
record GeoProperties(String baseUrl, Duration timeout, Duration cacheTtl) {
}
