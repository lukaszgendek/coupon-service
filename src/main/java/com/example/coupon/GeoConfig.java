package com.example.coupon;

import java.net.ProxySelector;
import java.net.http.HttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class GeoConfig {

	@Bean
	GeoLocationPort geoLocationPort(CountryLookup lookup, GeoProperties properties) {
		return new ResilientGeoLocation(lookup, properties.cacheTtl(), ResilientGeoLocation.newCircuitBreaker());
	}

	/** Selected with {@code coupon.geo.provider}; add a provider by adding a {@link CountryLookup} bean like this. */
	@Bean
	@ConditionalOnProperty(name = "coupon.geo.provider", havingValue = "ipwhois", matchIfMissing = true)
	CountryLookup ipWhoIsLookup(GeoProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.timeout())
				.proxy(ProxySelector.getDefault())
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.timeout());
		RestClient client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
		return new IpWhoIsLookup(client);
	}

}
