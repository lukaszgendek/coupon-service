package com.example.coupon.adapter.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressTest {

	@ParameterizedTest
	@ValueSource(strings = { "8.8.8.8", "203.0.113.7", "2001:db8::1", "2606:4700:4700::1111" })
	void publicAddressesAreAccepted(String ip) {
		assertThat(ClientAddress.publicAddress(ip)).isPresent();
	}

	@ParameterizedTest
	@ValueSource(strings = { "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.1.1",
			"0.0.0.0", "224.0.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1", "::ffff:10.0.0.1" })
	void localAndSpecialAddressesAreRejected(String ip) {
		assertThat(ClientAddress.publicAddress(ip)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "abc", "deadbeef", "1234", "1.2.3", "1.2.3.4.5", "999.1.1.1", "010.1.1.1",
			"evil.com", "8.8.8.8, 1.1.1.1", "8.8.8.8%eth0", "::zzzz", "1:2:3:4:5:6:7:8:9" })
	void notLiteralAddressesAreRejected(String ip) {
		assertThat(ClientAddress.publicAddress(ip)).isEmpty();
	}

	@Test
	void nullIsRejected() {
		assertThat(ClientAddress.publicAddress(null)).isEmpty();
	}

	@Test
	void differentWritingsOfOneIpv6AddressGiveOneCanonicalForm() {
		assertThat(ClientAddress.publicAddress("2001:db8::1")).isEqualTo(ClientAddress.publicAddress("2001:0db8:0:0:0:0:0:1"));
	}

}
