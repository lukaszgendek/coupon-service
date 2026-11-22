package com.example.coupon.adapter.geo;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Accepts only literal public IP addresses. The input is untrusted (it can come from X-Forwarded-For), so anything
 * else, including private and special ranges, is rejected before it can cost a provider request or reach the cache.
 */
final class ClientAddress {

	private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";

	private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");

	private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]{2,45}");

	private ClientAddress() {
	}

	/** The address in canonical form (so "::1" and "0:0:0:0:0:0:0:1" are one cache key), or empty if not public. */
	static Optional<String> publicAddress(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		// Only literals reach InetAddress: it would resolve any other string through DNS.
		boolean literal = IPV4.matcher(raw).matches() || (raw.indexOf(':') >= 0 && IPV6_CHARS.matcher(raw).matches());
		if (!literal) {
			return Optional.empty();
		}
		InetAddress address;
		try {
			address = InetAddress.getByName(raw);
		}
		catch (UnknownHostException e) {
			return Optional.empty();
		}
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress() || isUniqueLocalIpv6(address)) {
			return Optional.empty();
		}
		return Optional.of(address.getHostAddress());
	}

	/** fc00::/7, the IPv6 counterpart of the private IPv4 ranges, which the JDK does not recognise. */
	private static boolean isUniqueLocalIpv6(InetAddress address) {
		return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
	}

}
