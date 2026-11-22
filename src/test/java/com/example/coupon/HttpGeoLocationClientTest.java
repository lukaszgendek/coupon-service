package com.example.coupon;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpGeoLocationClientTest {

	private final RestClient.Builder builder = RestClient.builder().baseUrl("http://geo.test");

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	private final HttpGeoLocationClient client = new HttpGeoLocationClient(builder.build(), Duration.ofMinutes(10));

	@Test
	void parsesCountryAndCachesIt() {
		server.expect(once(), requestTo("http://geo.test/203.0.113.7?fields=success,country_code"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"success\":true,\"country_code\":\"PL\"}", MediaType.APPLICATION_JSON));

		assertThat(client.countryOf("203.0.113.7")).contains(CountryCode.of("PL"));
		assertThat(client.countryOf("203.0.113.7")).contains(CountryCode.of("PL"));

		server.verify();
	}

	@Test
	void unsuccessfulLookupIsEmpty() {
		server.expect(requestTo("http://geo.test/10.0.0.1?fields=success,country_code"))
				.andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));

		assertThat(client.countryOf("10.0.0.1")).isEmpty();
	}

	@Test
	void serverErrorIsEmptyAndNotCached() {
		server.expect(once(), requestTo("http://geo.test/203.0.113.8?fields=success,country_code"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		server.expect(once(), requestTo("http://geo.test/203.0.113.8?fields=success,country_code"))
				.andRespond(withSuccess("{\"success\":true,\"country_code\":\"DE\"}", MediaType.APPLICATION_JSON));

		assertThat(client.countryOf("203.0.113.8")).isEmpty();
		assertThat(client.countryOf("203.0.113.8")).contains(CountryCode.of("DE"));

		server.verify();
	}

}
