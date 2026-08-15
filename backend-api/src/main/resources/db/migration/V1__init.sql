CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE users (
    id               BIGSERIAL PRIMARY KEY,
    phone            VARCHAR(20)  NOT NULL UNIQUE,
    name             VARCHAR(100) NOT NULL,
    role             VARCHAR(10)  NOT NULL DEFAULT 'FRIEND'
                     CHECK (role IN ('FRIEND', 'ADMIN')),
    password_hash    VARCHAR(100),
    telegram_chat_id BIGINT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE bookings (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id),
    check_in     DATE        NOT NULL,
    check_out    DATE        NOT NULL,
    status       VARCHAR(15) NOT NULL
                 CHECK (status IN ('PENDING_OTP', 'CONFIRMED', 'CANCELLED')),
    comment      VARCHAR(500),
    cancelled_by VARCHAR(10) CHECK (cancelled_by IN ('GUEST', 'ADMIN')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (check_in < check_out),
    -- Полуинтервал [check_in, check_out): выезд и заезд в один день не конфликтуют.
    CONSTRAINT no_overlapping_bookings EXCLUDE USING gist (
        daterange(check_in, check_out) WITH &&
    ) WHERE (status IN ('PENDING_OTP', 'CONFIRMED'))
);

CREATE UNIQUE INDEX one_confirmed_booking_per_user
    ON bookings (user_id)
    WHERE status = 'CONFIRMED';

CREATE TABLE blocked_periods (
    id         BIGSERIAL PRIMARY KEY,
    start_date DATE NOT NULL,
    end_date   DATE NOT NULL,          -- включительно
    reason     VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (start_date <= end_date)
);

CREATE TABLE access_requests (
    id          BIGSERIAL PRIMARY KEY,
    phone       VARCHAR(20)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    message     VARCHAR(500),
    status      VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

CREATE TABLE otp_challenges (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    action     VARCHAR(25)  NOT NULL
               CHECK (action IN ('CREATE_BOOKING', 'RESCHEDULE', 'CANCEL',
                                 'ADMIN_PASSWORD_RESET')),
    payload    JSONB,
    code_hash  VARCHAR(100) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    attempts   INT          NOT NULL DEFAULT 0,
    status     VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
               CHECK (status IN ('PENDING', 'USED', 'EXPIRED')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        VARCHAR(50) NOT NULL,
    event_type   VARCHAR(40) NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
