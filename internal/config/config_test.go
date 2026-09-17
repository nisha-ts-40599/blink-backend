package config

import "testing"

func TestLocalMailOnWhenGateSetUnlessSMTPEnabled(t *testing.T) {
	on := Config{LoginGate: "gate"}
	if !on.LocalMail() {
		t.Fatal("expected LocalMail with a gate")
	}
	off := Config{LoginGate: "gate", SMTPEnabled: true}
	if off.LocalMail() {
		t.Fatal("SMTPEnabled must send real email")
	}
	if (Config{}).LocalMail() {
		t.Fatal("no gate means no LocalMail")
	}
}

func TestOTPRevealFromEnv(t *testing.T) {
	t.Setenv("BLINK_OTP_REVEAL", "")
	if !otpRevealFromEnv("talentserv-blink") {
		t.Fatal("expected reveal when gate is set and env is unset")
	}
	t.Setenv("BLINK_OTP_REVEAL", "false")
	if otpRevealFromEnv("talentserv-blink") {
		t.Fatal("explicit false")
	}
}
