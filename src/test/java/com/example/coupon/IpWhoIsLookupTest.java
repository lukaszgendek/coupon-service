package com.example.coupon;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Only the ipwho.is protocol: URL, JSON mapping and how failures are classified. */
class IpWhoIsLookupTest {

	private final RestClient.Builder builder = RestClient.builder().baseUrl("http://geo.test");

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	private final IpWhoIsLookup lookup = new IpWhoIsLookup(builder.build());

	@Test
	void mapsCountryCode() {
		server.expect(requestTo("http://geo.test/203.0.113.7?fields=success,country_code"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"success\":true,\"country_code\":\"pl\"}", MediaType.APPLICATION_JSON));

		assertThat(lookup.lookup("203.0.113.7")).isEqualTo(CountryCode.of("PL"));
	}

	@Test
	void unsuccessfulAnswerIsAnUnknownAddress() {
		server.expect(requestTo("http://geo.test/10.0.0.1?fields=success,country_code"))
				.andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> lookup.lookup("10.0.0.1")).isInstanceOf(CountryLookup.UnknownAddress.class);
	}

	@Test
	void errorStatusIsUnavailable() {
		server.expect(requestTo("http://geo.test/203.0.113.8?fields=success,country_code"))
				.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertThatThrownBy(() -> lookup.lookup("203.0.113.8")).isInstanceOf(CountryLookup.Unavailable.class);
	}

	@Test
	void malformedCountryCodeIsUnavailable() {
		server.expect(requestTo("http://geo.test/203.0.113.9?fields=success,country_code"))
				.andRespond(withSuccess("{\"success\":true,\"country_code\":\"POL\"}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> lookup.lookup("203.0.113.9")).isInstanceOf(CountryLookup.Unavailable.class);
	}

}
