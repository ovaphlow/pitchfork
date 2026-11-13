package shared_test

// 对照 service-nexus-shared/tests/identity_backed_resources.rs 与
// src/interactions/mod.rs：actor_id 取自会话、(actor,target,type) 唯一 409、
// 列表为裸数组、更新/删除只允许 actor 本人（他人 -> 404）。

import (
	"net/http"
	"testing"
)

func TestContractInteractionsCRUD(t *testing.T) {
	harness := newContractHarness(t)

	body := map[string]any{
		"target_type":      "care_plan",
		"target_id":        "plan-1",
		"interaction_type": "favorite",
		"payload":          map[string]any{},
	}

	created := harness.request(http.MethodPost, sharedPath("/interactions"), contractStaffSession, body)
	created.requireStatus(http.StatusCreated)
	interaction := created.object()
	interactionID := contractULIDField(t, interaction, "id")
	if got := contractString(t, interaction, "actor_id"); got != contractStaffID {
		t.Fatalf("actor_id = %q, want session subject %q", got, contractStaffID)
	}
	if got := contractString(t, interaction, "target_type"); got != "care_plan" {
		t.Fatalf("target_type = %q", got)
	}
	if got := contractString(t, interaction, "interaction_type"); got != "favorite" {
		t.Fatalf("interaction_type = %q", got)
	}
	contractTimestampField(t, interaction, "created_at")
	contractTimestampField(t, interaction, "updated_at")

	// (actor_id, target_type, target_id, interaction_type) 唯一 -> 409。
	duplicate := harness.request(http.MethodPost, sharedPath("/interactions"), contractStaffSession, body)
	duplicate.requireProblem(http.StatusConflict, "conflict", "")

	// 列表为裸数组，并支持 Rust 源码里的过滤参数。
	listed := harness.request(http.MethodGet, sharedPath("/interactions?target_type=care_plan"), contractStaffSession, nil)
	listed.requireStatus(http.StatusOK)
	if got := len(listed.array()); got != 1 {
		t.Fatalf("interactions list length = %d, want 1", got)
	}
	unrelatedFilter := harness.request(http.MethodGet, sharedPath("/interactions?target_type=unknown"), contractStaffSession, nil)
	unrelatedFilter.requireStatus(http.StatusOK)
	if got := len(unrelatedFilter.array()); got != 0 {
		t.Fatalf("filtered interactions length = %d, want 0", got)
	}

	fetched := harness.request(http.MethodGet, sharedPath("/interactions/"+interactionID), contractStaffSession, nil)
	fetched.requireStatus(http.StatusOK)
	if got := contractString(t, fetched.object(), "id"); got != interactionID {
		t.Fatalf("fetched id = %q", got)
	}

	// 本人更新 value。
	updated := harness.request(http.MethodPut, sharedPath("/interactions/"+interactionID), contractStaffSession, map[string]any{
		"value": 1.5,
	})
	updated.requireStatus(http.StatusOK)
	if got := contractNumber(t, updated.object(), "value"); got != 1.5 {
		t.Fatalf("updated value = %v, want 1.5", got)
	}

	// 他人不能改/删：所有权不匹配按 not found 处理（Nexus 语义）。
	otherUpdate := harness.request(http.MethodPut, sharedPath("/interactions/"+interactionID), contractManagerSession, map[string]any{
		"value": 9,
	})
	otherUpdate.requireProblem(http.StatusNotFound, "not-found", "")

	otherDelete := harness.request(http.MethodDelete, sharedPath("/interactions/"+interactionID), contractManagerSession, nil)
	otherDelete.requireProblem(http.StatusNotFound, "not-found", "")

	// 校验：必填字段 / payload 必须是对象 / 更新需要 value 或 payload。
	invalidCreate := harness.request(http.MethodPost, sharedPath("/interactions"), contractStaffSession, map[string]any{
		"target_type":      "",
		"target_id":        "plan-1",
		"interaction_type": "favorite",
		"payload":          map[string]any{},
	})
	invalidCreate.requireProblem(http.StatusBadRequest, "invalid-request", "")

	nonObjectPayload := harness.request(http.MethodPost, sharedPath("/interactions"), contractStaffSession, map[string]any{
		"target_type":      "care_plan",
		"target_id":        "plan-2",
		"interaction_type": "favorite",
		"payload":          "not-an-object",
	})
	nonObjectPayload.requireProblem(http.StatusBadRequest, "invalid-request", "")

	emptyUpdate := harness.request(http.MethodPut, sharedPath("/interactions/"+interactionID), contractStaffSession, map[string]any{})
	emptyUpdate.requireProblem(http.StatusBadRequest, "invalid-request", "")

	missing := harness.request(http.MethodGet, sharedPath("/interactions/not-a-real-id"), contractStaffSession, nil)
	missing.requireProblem(http.StatusNotFound, "not-found", "")

	// 本人删除 -> 204，再查 -> 404。
	deleted := harness.request(http.MethodDelete, sharedPath("/interactions/"+interactionID), contractStaffSession, nil)
	deleted.requireStatus(http.StatusNoContent)

	gone := harness.request(http.MethodGet, sharedPath("/interactions/"+interactionID), contractStaffSession, nil)
	gone.requireProblem(http.StatusNotFound, "not-found", "")

	deleteMissing := harness.request(http.MethodDelete, sharedPath("/interactions/"+interactionID), contractStaffSession, nil)
	deleteMissing.requireProblem(http.StatusNotFound, "not-found", "")
}
