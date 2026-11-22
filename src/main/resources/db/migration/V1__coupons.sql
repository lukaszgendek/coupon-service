CREATE TABLE coupon (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(64) NOT NULL,
    country    CHAR(2)     NOT NULL,
    max_uses   INT         NOT NULL,
    used_count INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_coupon_code UNIQUE (code),
    CONSTRAINT ck_coupon_code_upper CHECK (code = upper(code)),
    CONSTRAINT ck_coupon_max_uses CHECK (max_uses > 0),
    CONSTRAINT ck_coupon_used_count CHECK (used_count >= 0 AND used_count <= max_uses)
);

CREATE TABLE coupon_usage (
    coupon_id BIGINT      NOT NULL REFERENCES coupon (id),
    user_id   VARCHAR(64) NOT NULL,
    used_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (coupon_id, user_id)
);
