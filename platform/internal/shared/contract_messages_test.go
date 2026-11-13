package shared_test

// 对照 service-nexus-shared/src/messages/mod.rs：列表为裸数组、sender_id 取自会话、
// status 默认 unread、PATCH /{id}/status、更新需要 status 或 payload。

import (
	"net/http"
	"testing"
)

func TestContractMessagesCRUD(t *testing.T) {
	harness := newContractHarness(t)

	created := harness.request(http.MethodPost, sharedPath("/messages"), contractStaffSession, map[string]any{
		"message_type":  "system_notification",
		"sender_type":   "user",
		"receiver_id":   "resident-1",
		"receiver_type": "resident",
		"payload":       map[string]any{"title": "Reminder"},
	})
	created.requireStatus(http.StatusCreated)
	message := created.object()
	messageID := contractULIDField(t, message, "id")
	if got := contractString(t, message, "sender_id"); got != contractStaffID {
		t.Fatalf("sender_id = %q, want session subject %q", got, contractStaffID)
	}
	if got := contractString(t, message, "status"); got != "unread" {
		t.Fatalf("status = %q, want unread default", got)
	}
	if got := contractString(t, message, "message_type"); got != "system_notification" {
		t.Fatalf("message_type = %q", got)
	}
	if payload := contractObjectField(t, message, "payload"); contractString(t, payload, "title") != "Reminder" {
		t.Fatalf("payload = %v", payload)
	}
	contractTimestampField(t, message, "created_at")
	contractTimestampField(t, message, "updated_at")

	listed := harness.request(http.MethodGet, sharedPath("/messages"), contractStaffSession, nil)
	listed.requireStatus(http.StatusOK)
	if got := len(listed.array()); got != 1 {
		t.Fatalf("messages list length = %d, want 1", got)
	}

	fetched := harness.request(http.MethodGet, sharedPath("/messages/"+messageID), contractStaffSession, nil)
	fetched.requireStatus(http.StatusOK)
	if got := contractString(t, fetched.object(), "id"); got != messageID {
		t.Fatalf("fetched id = %q", got)
	}

	// PATCH /{id}/status。
	patched := harness.request(http.MethodPatch, sharedPath("/messages/"+messageID+"/status"), contractStaffSession, map[string]any{
		"status": "read",
	})
	patched.requireStatus(http.StatusOK)
	if got := contractString(t, patched.object(), "status"); got != "read" {
		t.Fatalf("patched status = %q, want read", got)
	}

	// PUT 全量更新（status + payload）。
	updated := harness.request(http.MethodPut, sharedPath("/messages/"+messageID), contractStaffSession, map[string]any{
		"status":  "archived",
		"payload": map[string]any{"title": "Updated"},
	})
	updated.requireStatus(http.StatusOK)
	updatedMessage := updated.object()
	if got := contractString(t, updatedMessage, "status"); got != "archived" {
		t.Fatalf("updated status = %q", got)
	}
	if payload := contractObjectField(t, updatedMessage, "payload"); contractString(t, payload, "title") != "Updated" {
		t.Fatalf("updated payload = %v", payload)
	}

	// PUT 既无 status 也无 payload -> 400。
	emptyUpdate := harness.request(http.MethodPut, sharedPath("/messages/"+messageID), contractStaffSession, map[string]any{})
	emptyUpdate.requireProblem(http.StatusBadRequest, "invalid-request", "")

	// 创建校验：缺字段 / payload 非对象 -> 400。
	missingField := harness.request(http.MethodPost, sharedPath("/messages"), contractStaffSession, map[string]any{
		"message_type": "system_notification",
		"sender_type":  "user",
		"receiver_id":  "resident-1",
		"payload":      map[string]any{},
	})
	missingField.requireProblem(http.StatusBadRequest, "invalid-request", "")

	nonObjectPayload := harness.request(http.MethodPost, sharedPath("/messages"), contractStaffSession, map[string]any{
		"message_type":  "system_notification",
		"sender_type":   "user",
		"receiver_id":   "resident-1",
		"receiver_type": "resident",
		"payload":       []string{"nope"},
	})
	nonObjectPayload.requireProblem(http.StatusBadRequest, "invalid-request", "")

	missing := harness.request(http.MethodGet, sharedPath("/messages/not-a-real-id"), contractStaffSession, nil)
	missing.requireProblem(http.StatusNotFound, "not-found", "")

	patchedMissing := harness.request(http.MethodPatch, sharedPath("/messages/not-a-real-id/status"), contractStaffSession, map[string]any{
		"status": "read",
	})
	patchedMissing.requireProblem(http.StatusNotFound, "not-found", "")

	deleted := harness.request(http.MethodDelete, sharedPath("/messages/"+messageID), contractStaffSession, nil)
	deleted.requireStatus(http.StatusNoContent)

	gone := harness.request(http.MethodGet, sharedPath("/messages/"+messageID), contractStaffSession, nil)
	gone.requireProblem(http.StatusNotFound, "not-found", "")

	deleteMissing := harness.request(http.MethodDelete, sharedPath("/messages/"+messageID), contractStaffSession, nil)
	deleteMissing.requireProblem(http.StatusNotFound, "not-found", "")
}
