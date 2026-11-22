package com.example.coupon;

import java.net.ProxySelector;
import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class GeoConfig {

	@Bean
	GeoLocationPort geoLocationPort(GeoProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.timeout())
				.proxy(ProxySelector.getDefault())
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.timeout());
		RestClient client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
		return new HttpGeoLocationClient(client, properties.cacheTtl());
	}

}
