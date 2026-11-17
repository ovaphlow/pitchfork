package shared_test

// messages 模块的开发自测（task-10）：sender_id 派生、status 默认值、
// 列表过滤与 created_at DESC 排序、PUT/PATCH 语义、错误形状。

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/platform/internal/shared"
)

type messageResponse struct {
	ID           string         `json:"id"`
	MessageType  string         `json:"message_type"`
	SenderID     string         `json:"sender_id"`
	SenderType   string         `json:"sender_type"`
	ReceiverID   string         `json:"receiver_id"`
	ReceiverType string         `json:"receiver_type"`
	Status       string         `json:"status"`
	Payload      map[string]any `json:"payload"`
	CreatedAt    string         `json:"created_at"`
	UpdatedAt    string         `json:"updated_at"`
}

func (harness sharedModulesHarness) createMessage(t *testing.T, body map[string]any) messageResponse {
	t.Helper()
	recorder := harness.request(t, http.MethodPost, shared.Prefix+"/messages", sharedModulesSubjectSession, body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("create message status = %d (body %s)", recorder.Code, recorder.Body.String())
	}
	return sharedModulesDecode[messageResponse](t, recorder)
}

func TestMessagesSenderDerivationDefaultsAndValidation(t *testing.T) {
	harness := newSharedModulesHarness(t)

	created := harness.createMessage(t, map[string]any{
		"message_type":  "system_notification",
		"sender_type":   "user",
		"sender_id":     "forged-sender", // 客户端伪造 —— 必须被忽略
		"receiver_id":   "resident-1",
		"receiver_type": "resident",
		"payload":       map[string]any{"title": "Reminder"},
	})
	sharedModulesRequireULID(t, created.ID)
	if created.SenderID != sharedModulesSubjectID {
		t.Fatalf("sender_id = %q, want %q", created.SenderID, sharedModulesSubjectID)
	}
	if created.Status != "unread" {
		t.Fatalf("status = %q, want unread default", created.Status)
	}
	if created.MessageType != "system_notification" || created.SenderType != "user" || created.ReceiverID != "resident-1" || created.ReceiverType != "resident" {
		t.Fatalf("created message = %#v", created)
	}
	if created.Payload["title"] != "Reminder" {
		t.Fatalf("payload = %v", created.Payload)
	}
	sharedModulesRequireTimestamp(t, created.CreatedAt)
	sharedModulesRequireTimestamp(t, created.UpdatedAt)

	// payload 缺省 -> {}（Rust 的 serde default）。
	withoutPayload := harness.createMessage(t, map[string]any{
		"message_type":  "notice",
		"sender_type":   "system",
		"receiver_id":   "resident-2",
		"receiver_type": "resident",
	})
	if len(withoutPayload.Payload) != 0 {
		t.Fatalf("default payload = %v, want {}", withoutPayload.Payload)
	}

	// 必填字段缺失 -> 400，detail 与 Nexus 一致。
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPost, shared.Prefix+"/messages", sharedModulesSubjectSession, map[string]any{
		"message_type": "notice",
		"sender_type":  "system",
		"receiver_id":  "resident-2",
	}), http.StatusBadRequest, "invalid-request", "message_type, sender_type, receiver_id, and receiver_type are required")

	// payload 必须是 JSON 对象。
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPost, shared.Prefix+"/messages", sharedModulesSubjectSession, map[string]any{
		"message_type":  "notice",
		"sender_type":   "system",
		"receiver_id":   "resident-2",
		"receiver_type": "resident",
		"payload":       []any{"nope"},
	}), http.StatusBadRequest, "invalid-request", "payload must be a JSON object")

	// 无会话 -> 401；尾斜杠兼容。
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPost, shared.Prefix+"/messages", "", map[string]any{}), http.StatusUnauthorized, "not-authenticated", "not authenticated")
	trailing := harness.request(t, http.MethodPost, shared.Prefix+"/messages/", sharedModulesSubjectSession, map[string]any{
		"message_type":  "notice",
		"sender_type":   "system",
		"receiver_id":   "resident-3",
		"receiver_type": "resident",
	})
	if trailing.Code != http.StatusCreated {
		t.Fatalf("trailing-slash create status = %d (body %s)", trailing.Code, trailing.Body.String())
	}
}

func TestMessagesListFiltersOrderingAndPagination(t *testing.T) {
	harness := newSharedModulesHarness(t)
	seed := func(index int, messageType, sender, receiver, status, createdAt string) string {
		t.Helper()
		id := seedID("01J", index)
		harness.seed(t, `
			INSERT INTO messages (id, app_code, message_type, sender_id, sender_type, receiver_id, receiver_type, status, payload, created_at, updated_at)
			VALUES (?, 'aceso', ?, ?, 'user', ?, 'resident', ?, '{"title":"seeded"}', ?, ?)`,
			id, messageType, sender, receiver, status, createdAt, createdAt)
		return id
	}
	oldest := seed(1, "notice", "sender-a", "receiver-a", "unread", "2026-01-01T00:00:01.000Z")
	middle := seed(2, "alert", "sender-b", "receiver-b", "read", "2026-01-01T00:00:02.000Z")
	newest := seed(3, "alert", "sender-b", "receiver-a", "read", "2026-01-01T00:00:03.000Z")

	listIDs := func(query string) []string {
		t.Helper()
		recorder := harness.request(t, http.MethodGet, shared.Prefix+"/messages"+query, sharedModulesSubjectSession, nil)
		if recorder.Code != http.StatusOK {
			t.Fatalf("list messages%s status = %d (body %s)", query, recorder.Code, recorder.Body.String())
		}
		raw := sharedModulesDecodeArray(t, recorder)
		ids := make([]string, 0, len(raw))
		for _, value := range raw {
			var message messageResponse
			if err := json.Unmarshal(value, &message); err != nil {
				t.Fatalf("decode listed message: %v", err)
			}
			ids = append(ids, message.ID)
		}
		return ids
	}

	// ORDER BY created_at DESC。
	all := listIDs("")
	if len(all) != 3 || all[0] != newest || all[1] != middle || all[2] != oldest {
		t.Fatalf("messages order = %v, want [%s %s %s]", all, newest, middle, oldest)
	}
	if got := listIDs("?message_type=alert"); len(got) != 2 {
		t.Fatalf("message_type filter length = %d, want 2", len(got))
	}
	if got := listIDs("?status=unread"); len(got) != 1 || got[0] != oldest {
		t.Fatalf("status filter = %v, want [%s]", got, oldest)
	}
	if got := listIDs("?sender_id=sender-b"); len(got) != 2 {
		t.Fatalf("sender_id filter length = %d, want 2", len(got))
	}
	if got := listIDs("?receiver_id=receiver-a"); len(got) != 2 {
		t.Fatalf("receiver_id filter length = %d, want 2", len(got))
	}
	// 空过滤值等同未提供（Rust 的 filter(|v| !v.is_empty())）。
	if got := listIDs("?status="); len(got) != 3 {
		t.Fatalf("empty status filter length = %d, want 3", len(got))
	}

	// 分页作用在过滤后的排序结果上。
	if got := listIDs("?page=1&page_size=1"); len(got) != 1 || got[0] != newest {
		t.Fatalf("page 1 = %v, want [%s]", got, newest)
	}
	if got := listIDs("?page=2&page_size=1"); len(got) != 1 || got[0] != middle {
		t.Fatalf("page 2 = %v, want [%s]", got, middle)
	}
	// 非法分页 -> 400。
	sharedModulesRequireProblem(t, harness.request(t, http.MethodGet, shared.Prefix+"/messages?page_size=0", sharedModulesSubjectSession, nil), http.StatusBadRequest, "invalid-request", "page must be positive and page_size must be between 1 and 100")
}

func TestMessagesUpdatePatchAndDelete(t *testing.T) {
	harness := newSharedModulesHarness(t)
	created := harness.createMessage(t, map[string]any{
		"message_type":  "notice",
		"sender_type":   "user",
		"receiver_id":   "resident-1",
		"receiver_type": "resident",
		"payload":       map[string]any{"title": "Original"},
	})

	// PUT 只给 payload：status 保持 unread。
	payloadOnly := sharedModulesDecode[messageResponse](t, harness.request(t, http.MethodPut, shared.Prefix+"/messages/"+created.ID, sharedModulesSubjectSession, map[string]any{
		"payload": map[string]any{"title": "Payload only"},
	}))
	if payloadOnly.Status != "unread" || payloadOnly.Payload["title"] != "Payload only" {
		t.Fatalf("payload-only update = %#v", payloadOnly)
	}
	sharedModulesRequireTimestamp(t, payloadOnly.UpdatedAt)

	// PUT 只给 status：payload 保持上一步的值。
	statusOnly := sharedModulesDecode[messageResponse](t, harness.request(t, http.MethodPut, shared.Prefix+"/messages/"+created.ID, sharedModulesSubjectSession, map[string]any{
		"status": "read",
	}))
	if statusOnly.Status != "read" || statusOnly.Payload["title"] != "Payload only" {
		t.Fatalf("status-only update = %#v", statusOnly)
	}

	sharedModulesRequireProblem(t, harness.request(t, http.MethodPut, shared.Prefix+"/messages/"+created.ID, sharedModulesSubjectSession, map[string]any{}), http.StatusBadRequest, "invalid-request", "status or payload is required")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPut, shared.Prefix+"/messages/"+created.ID, sharedModulesSubjectSession, map[string]any{"status": "  "}), http.StatusBadRequest, "invalid-request", "status cannot be empty")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPut, shared.Prefix+"/messages/"+created.ID, sharedModulesSubjectSession, map[string]any{"payload": "text"}), http.StatusBadRequest, "invalid-request", "payload must be a JSON object")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPut, shared.Prefix+"/messages/not-a-real-id", sharedModulesSubjectSession, map[string]any{"status": "read"}), http.StatusNotFound, "not-found", "message not found")

	// PATCH /{id}/status。
	patched := sharedModulesDecode[messageResponse](t, harness.request(t, http.MethodPatch, shared.Prefix+"/messages/"+created.ID+"/status", sharedModulesSubjectSession, map[string]any{"status": "archived"}))
	if patched.Status != "archived" || patched.Payload["title"] != "Payload only" {
		t.Fatalf("patched message = %#v", patched)
	}
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPatch, shared.Prefix+"/messages/"+created.ID+"/status", sharedModulesSubjectSession, map[string]any{"status": ""}), http.StatusBadRequest, "invalid-request", "status is required")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPatch, shared.Prefix+"/messages/not-a-real-id/status", sharedModulesSubjectSession, map[string]any{"status": "read"}), http.StatusNotFound, "not-found", "message not found")

	// DELETE -> 204，再查/再删 -> 404。
	if recorder := harness.request(t, http.MethodDelete, shared.Prefix+"/messages/"+created.ID, sharedModulesSubjectSession, nil); recorder.Code != http.StatusNoContent {
		t.Fatalf("delete status = %d (body %s)", recorder.Code, recorder.Body.String())
	}
	sharedModulesRequireProblem(t, harness.request(t, http.MethodGet, shared.Prefix+"/messages/"+created.ID, sharedModulesSubjectSession, nil), http.StatusNotFound, "not-found", "message not found")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodDelete, shared.Prefix+"/messages/"+created.ID, sharedModulesSubjectSession, nil), http.StatusNotFound, "not-found", "message not found")

	// 列表为空时仍是裸数组 []。
	empty := harness.request(t, http.MethodGet, shared.Prefix+"/messages", sharedModulesSubjectSession, nil)
	if strings.TrimSpace(empty.Body.String()) != "[]" {
		t.Fatalf("empty messages body = %q, want []", empty.Body.String())
	}
}
