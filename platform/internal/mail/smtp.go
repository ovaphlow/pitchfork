// Package mail 是身份服务唯一的邮件投递实现。
//
// 它只做一件事：把一次性登录链接交给 SMTP 中继。错误文案刻意保持固定，
// 不携带收件人或链接——SMTP 响应可能回显地址，调用方会记录返回的错误。
package mail

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"fmt"
	"mime"
	"net"
	"net/mail"
	"net/smtp"
	"strings"
	"time"
)

// TLS 模式取值。starttls 是默认，implicit 用于 465 端口，none 仅用于本机中继。
const (
	TLSModeStartTLS = "starttls"
	TLSModeImplicit = "implicit"
	TLSModeNone     = "none"
)

type Config struct {
	Address  string
	From     string
	Username string
	Password string
	TLSMode  string
	Timeout  time.Duration
}

type SMTPMailer struct {
	config Config
	from   mail.Address
	host   string
}

// NewSMTPMailer 校验配置并解析发件人。
func NewSMTPMailer(config Config) (*SMTPMailer, error) {
	config.Address = strings.TrimSpace(config.Address)
	config.From = strings.TrimSpace(config.From)
	config.Username = strings.TrimSpace(config.Username)
	config.TLSMode = strings.ToLower(strings.TrimSpace(config.TLSMode))
	if config.TLSMode == "" {
		config.TLSMode = TLSModeStartTLS
	}

	host, _, err := net.SplitHostPort(config.Address)
	if err != nil || strings.TrimSpace(host) == "" {
		return nil, fmt.Errorf("SMTP address must be host:port")
	}

	from, err := mail.ParseAddress(config.From)
	if err != nil {
		return nil, fmt.Errorf("SMTP from address is invalid: %w", err)
	}

	switch config.TLSMode {
	case TLSModeStartTLS, TLSModeImplicit, TLSModeNone:
	default:
		return nil, fmt.Errorf("SMTP TLS mode must be starttls, implicit or none")
	}
	if config.Timeout <= 0 {
		return nil, fmt.Errorf("SMTP timeout must be positive")
	}
	if (config.Username == "") != (config.Password == "") {
		return nil, fmt.Errorf("SMTP username and password must be set together")
	}
	if config.TLSMode == TLSModeNone && config.Username != "" {
		return nil, fmt.Errorf("SMTP credentials require TLS")
	}

	return &SMTPMailer{config: config, from: *from, host: host}, nil
}

// SendEmailLoginLink 同步发送一封登录邮件。所有错误文案都不含收件人与链接。
func (m *SMTPMailer) SendEmailLoginLink(ctx context.Context, to string, link string, ttl time.Duration) error {
	recipient, err := parseRecipient(to)
	if err != nil {
		return err
	}
	message := buildEmailLoginMessage(m.from, to, link, ttl, time.Now().UTC())

	dialer := &net.Dialer{Timeout: m.config.Timeout}
	connection, err := dialer.DialContext(ctx, "tcp", m.config.Address)
	if err != nil {
		return fmt.Errorf("smtp connect failed")
	}
	defer connection.Close()
	if err := connection.SetDeadline(time.Now().Add(m.config.Timeout)); err != nil {
		return fmt.Errorf("smtp deadline failed")
	}

	var client *smtp.Client
	if m.config.TLSMode == TLSModeImplicit {
		tlsConnection := tls.Client(connection, &tls.Config{ServerName: m.host, MinVersion: tls.VersionTLS12})
		if err := tlsConnection.HandshakeContext(ctx); err != nil {
			return fmt.Errorf("smtp TLS handshake failed")
		}
		client, err = smtp.NewClient(tlsConnection, m.host)
	} else {
		client, err = smtp.NewClient(connection, m.host)
	}
	if err != nil {
		return fmt.Errorf("smtp greeting failed")
	}
	defer client.Close()

	if m.config.TLSMode == TLSModeStartTLS {
		if supported, _ := client.Extension("STARTTLS"); !supported {
			return fmt.Errorf("smtp server does not support STARTTLS")
		}
		if err := client.StartTLS(&tls.Config{ServerName: m.host, MinVersion: tls.VersionTLS12}); err != nil {
			return fmt.Errorf("smtp STARTTLS failed")
		}
	}

	if m.config.Username != "" {
		if err := client.Auth(smtp.PlainAuth("", m.config.Username, m.config.Password, m.host)); err != nil {
			return fmt.Errorf("smtp authentication failed")
		}
	}

	if err := client.Mail(m.from.Address); err != nil {
		return fmt.Errorf("smtp MAIL FROM failed")
	}
	if err := client.Rcpt(recipient); err != nil {
		return fmt.Errorf("smtp RCPT failed")
	}
	writer, err := client.Data()
	if err != nil {
		return fmt.Errorf("smtp DATA failed")
	}
	if _, err := writer.Write(message); err != nil {
		return fmt.Errorf("smtp message write failed")
	}
	if err := writer.Close(); err != nil {
		return fmt.Errorf("smtp message close failed")
	}
	if err := client.Quit(); err != nil {
		return fmt.Errorf("smtp quit failed")
	}
	return nil
}

// parseRecipient 只接受纯地址，并拒绝任何含 CR/LF 的值（防头注入）。
func parseRecipient(value string) (string, error) {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" || strings.ContainsAny(trimmed, "\r\n") {
		return "", fmt.Errorf("smtp recipient is invalid")
	}
	address, err := mail.ParseAddress(trimmed)
	if err != nil {
		return "", fmt.Errorf("smtp recipient is invalid")
	}
	return address.Address, nil
}

// buildEmailLoginMessage 构造纯文本登录邮件。抽成函数以便测试直接校验内容。
//
// 头部用 CRLF，正文 UTF-8 后 base64（每 76 字符折行），与 MIME 声明一致。
func buildEmailLoginMessage(from mail.Address, to string, link string, ttl time.Duration, now time.Time) []byte {
	ttlMinutes := int(ttl / time.Minute)
	if ttlMinutes < 1 {
		ttlMinutes = 1
	}
	body := fmt.Sprintf(
		"你好：\n\n点击下面的链接登录你的账号。链接 %d 分钟内有效，且只能使用一次。\n\n%s\n\n如果不是你发起的请求，请忽略这封邮件。\n",
		ttlMinutes,
		link,
	)

	var message strings.Builder
	for _, header := range []string{
		"From: " + from.String(),
		"To: " + to,
		"Subject: " + mime.QEncoding.Encode("UTF-8", "登录链接"),
		"Date: " + now.Format(time.RFC1123Z),
		"MIME-Version: 1.0",
		"Content-Type: text/plain; charset=UTF-8",
		"Content-Transfer-Encoding: base64",
	} {
		message.WriteString(header)
		message.WriteString("\r\n")
	}
	message.WriteString("\r\n")

	encoded := base64.StdEncoding.EncodeToString([]byte(body))
	for start := 0; start < len(encoded); start += 76 {
		end := start + 76
		if end > len(encoded) {
			end = len(encoded)
		}
		message.WriteString(encoded[start:end])
		message.WriteString("\r\n")
	}
	return []byte(message.String())
}
