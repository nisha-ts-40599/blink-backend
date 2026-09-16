package config

import "testing"

func TestOTPRevealDefaultsOnWhenLoginGateSet(t *testing.T) {
	t.Setenv("BLINK_OTP_REVEAL", "")
	if !otpRevealFromEnv("talentserv-blink") {
		t.Fatal("expected reveal when gate is set and env is unset")
	}
	if otpRevealFromEnv("") {
		t.Fatal("expected no reveal without a gate")
	}
}

func TestOTPRevealFalseDisablesDemoMail(t *testing.T) {
	t.Setenv("BLINK_OTP_REVEAL", "false")
	if otpRevealFromEnv("talentserv-blink") {
		t.Fatal("BLINK_OTP_REVEAL=false must disable on-screen codes")
	}
	cfg := Config{OTPReveal: false, LoginGate: "talentserv-blink"}
	if cfg.LocalMail() {
		t.Fatal("LocalMail should be off when reveal is false")
	}
}

func TestLocalMailRequiresGateAndReveal(t *testing.T) {
	on := Config{OTPReveal: true, LoginGate: "gate"}
	if !on.LocalMail() {
		t.Fatal("expected LocalMail")
	}
	if (Config{OTPReveal: true}).LocalMail() {
		t.Fatal("reveal without gate is not LocalMail")
	}
}
