-- ОТП переезжает с подтверждения брони на вход (этап 6.6)
ALTER TABLE otp_challenges DROP CONSTRAINT otp_challenges_action_check;
ALTER TABLE otp_challenges ADD CONSTRAINT otp_challenges_action_check
    CHECK (action IN ('CREATE_BOOKING', 'RESCHEDULE', 'CANCEL',
                      'ADMIN_PASSWORD_RESET', 'LOGIN'));
