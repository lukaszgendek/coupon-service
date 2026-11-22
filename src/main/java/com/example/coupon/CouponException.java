package com.example.coupon;

/** Business failure of a coupon operation. Unchecked, so it also rolls back the surrounding transaction. */
public abstract sealed class CouponException extends RuntimeException {

	protected CouponException(String message) {
		super(message);
	}

	public static final class InvalidInput extends CouponException {
		public InvalidInput(String message) {
			super(message);
		}
	}

	public static final class NotFound extends CouponException {
		public NotFound() {
			super("Coupon does not exist");
		}
	}

	public static final class Exhausted extends CouponException {
		public Exhausted() {
			super("Coupon has reached its maximum number of uses");
		}
	}

	public static final class AlreadyUsed extends CouponException {
		public AlreadyUsed() {
			super("Coupon has already been used by this user");
		}
	}

	public static final class CountryNotAllowed extends CouponException {
		public CountryNotAllowed() {
			super("Coupon cannot be used from your country");
		}
	}

	public static final class DuplicateCode extends CouponException {
		public DuplicateCode() {
			super("Coupon with this code already exists");
		}
	}

	public static final class CountryUnknown extends CouponException {
		public CountryUnknown() {
			super("Could not determine the country of the request");
		}
	}

}
