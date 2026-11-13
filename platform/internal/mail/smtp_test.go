package mail

import (
	"encoding/base64"
	"mime"
	"net/mail"
	"strings"
	"testing"
	"time"
)

func TestBuildEmailLoginMessageEncodesLoginLink(t *testing.T) {
	from := mail.Address{Address: "no-reply@example.test"}
	now := time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC)
	link := "http://localhost:4321/?email_token=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
	message := buildEmailLoginMessage(from, "user@example.com", link, 10*time.Minute, now)

	header, encodedBody, ok := strings.Cut(string(message), "\r\n\r\n")
	if !ok {
		t.Fatalf("message has no header/body separator:\n%s", message)
	}
	for _, expected := range []string{
		"From: <no-reply@example.test>",
		"To: user@example.com",
		"Date: Tue, 29 Sep 2026 12:00:00 +0000",
		"MIME-Version: 1.0",
		"Content-Type: text/plain; charset=UTF-8",
		"Content-Transfer-Encoding: base64",
	} {
		if !strings.Contains(header, expected) {
			t.Errorf("header missing %q:\n%s", expected, header)
		}
	}

	var subject string
	for _, line := range strings.Split(header, "\r\n") {
		if value, found := strings.CutPrefix(line, "Subject: "); found {
			decoded, err := new(mime.WordDecoder).DecodeHeader(value)
			if err != nil {
				t.Fatalf("decode subject: %v", err)
			}
			subject = decoded
		}
	}
	if subject != "登录链接" {
		t.Errorf("subject = %q, want 登录链接", subject)
	}

	decodedBody, err := base64.StdEncoding.DecodeString(strings.ReplaceAll(encodedBody, "\r\n", ""))
	if err != nil {
		t.Fatalf("decode body: %v", err)
	}
	body := string(decodedBody)
	if !strings.Contains(body, link) {
		t.Errorf("body does not contain the link:\n%s", body)
	}
	if !strings.Contains(body, "10 分钟内有效") {
		t.Errorf("body does not mention the TTL:\n%s", body)
	}

	for _, line := range strings.Split(strings.TrimSuffix(encodedBody, "\r\n"), "\r\n") {
		if len(line) > 76 {
			t.Errorf("base64 line of length %d exceeds 76", len(line))
		}
	}
}

func TestBuildEmailLoginMessageClampsSubMinuteTTL(t *testing.T) {
	from := mail.Address{Address: "no-reply@example.test"}
	message := string(buildEmailLoginMessage(from, "user@example.com", "http://localhost:4321/?email_token=x", 20*time.Second, time.Now().UTC()))
	_, encodedBody, _ := strings.Cut(message, "\r\n\r\n")
	decodedBody, err := base64.StdEncoding.DecodeString(strings.ReplaceAll(encodedBody, "\r\n", ""))
	if err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if !strings.Contains(string(decodedBody), "1 分钟内有效") {
		t.Errorf("body = %s", decodedBody)
	}
}

func TestNewSMTPMailerValidatesConfig(t *testing.T) {
	valid := Config{
		Address: "127.0.0.1:1025",
		From:    "no-reply@example.test",
		TLSMode: TLSModeNone,
		Timeout: time.Second,
	}

	tests := []struct {
		name    string
		mutate  func(*Config)
		wantErr bool
	}{
		{name: "valid plain relay", mutate: func(*Config) {}},
		{name: "default TLS mode is starttls", mutate: func(config *Config) {
			config.TLSMode = ""
		}},
		{name: "implicit with credentials", mutate: func(config *Config) {
			config.TLSMode = TLSModeImplicit
			config.Username = "relay"
			config.Password = "secret"
		}},
		{name: "address without port", mutate: func(config *Config) { config.Address = "127.0.0.1" }, wantErr: true},
		{name: "invalid from", mutate: func(config *Config) { config.From = "not an address" }, wantErr: true},
		{name: "unsupported TLS mode", mutate: func(config *Config) { config.TLSMode = "ssl" }, wantErr: true},
		{name: "zero timeout", mutate: func(config *Config) { config.Timeout = 0 }, wantErr: true},
		{name: "username without password", mutate: func(config *Config) {
			config.TLSMode = TLSModeStartTLS
			config.Username = "relay"
		}, wantErr: true},
		{name: "credentials without TLS", mutate: func(config *Config) {
			config.Username = "relay"
			config.Password = "secret"
		}, wantErr: true},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			config := valid
			test.mutate(&config)
			mailer, err := NewSMTPMailer(config)
			if test.wantErr {
				if err == nil {
					t.Fatalf("NewSMTPMailer(%+v) = nil error, want failure", config)
				}
				return
			}
			if err != nil {
				t.Fatalf("NewSMTPMailer(%+v): %v", config, err)
			}
			if mailer.from.Address != "no-reply@example.test" || mailer.host != "127.0.0.1" {
				t.Fatalf("mailer = %+v", mailer)
			}
			if config.TLSMode == "" && mailer.config.TLSMode != TLSModeStartTLS {
				t.Fatalf("TLS mode = %q, want %q", mailer.config.TLSMode, TLSModeStartTLS)
			}
		})
	}
}

func TestSendEmailLoginLinkRejectsInjectedRecipient(t *testing.T) {
	mailer, err := NewSMTPMailer(Config{
		Address: "127.0.0.1:1025",
		From:    "no-reply@example.test",
		TLSMode: TLSModeNone,
		Timeout: time.Second,
	})
	if err != nil {
		t.Fatalf("NewSMTPMailer: %v", err)
	}
	if err := mailer.SendEmailLoginLink(t.Context(), "user@example.com\r\nBcc: someone@example.com", "http://localhost:4321/?email_token=x", time.Minute); err == nil {
		t.Fatal("recipient with CRLF was accepted")
	}
}
