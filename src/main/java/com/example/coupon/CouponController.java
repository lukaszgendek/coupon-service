package com.example.coupon;

import java.net.URI;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupons")
class CouponController {

	private final CouponService service;

	CouponController(CouponService service) {
		this.service = service;
	}

	@PostMapping
	ResponseEntity<CouponResponse> create(@Valid @RequestBody CreateCouponRequest request) {
		Coupon coupon = service.create(CouponCode.of(request.code()), CountryCode.of(request.country()), request.maxUses());
		return ResponseEntity.created(URI.create("/api/coupons/" + coupon.code().value())).body(CouponResponse.from(coupon));
	}

	@GetMapping("/{code}")
	CouponResponse get(@PathVariable String code) {
		return CouponResponse.from(service.get(CouponCode.of(code)));
	}

	/** The client address comes from the servlet container, which honours X-Forwarded-For from trusted proxies only. */
	@PostMapping("/{code}/usages")
	ResponseEntity<Void> use(@PathVariable String code, @Valid @RequestBody UseCouponRequest request,
			HttpServletRequest http) {
		service.use(CouponCode.of(code), request.userId(), http.getRemoteAddr());
		return ResponseEntity.noContent().build();
	}

	record CreateCouponRequest(@NotBlank @Size(max = 64) String code, @NotBlank String country, @Min(1) int maxUses) {
	}

	record UseCouponRequest(@NotBlank @Size(max = 64) String userId) {
	}

	record CouponResponse(String code, String country, int maxUses, int usedCount, Instant createdAt) {

		static CouponResponse from(Coupon coupon) {
			return new CouponResponse(coupon.code().value(), coupon.country().value(), coupon.maxUses(),
					coupon.usedCount(), coupon.createdAt());
		}

	}

}
