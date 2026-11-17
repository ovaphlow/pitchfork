package shared_test

// interactions 模块的开发自测（task-10）：actor_id 派生、UNIQUE 四元组 409、
// 列表过滤与 created_at DESC 排序、value/payload 局部更新、所有权 404 语义。

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/platform/internal/shared"
)

type interactionResponse struct {
	ID              string         `json:"id"`
	ActorID         string         `json:"actor_id"`
	TargetType      string         `json:"target_type"`
	TargetID        string         `json:"target_id"`
	InteractionType string         `json:"interaction_type"`
	Value           *float64       `json:"value"`
	Payload         map[string]any `json:"payload"`
	CreatedAt       string         `json:"created_at"`
	UpdatedAt       string         `json:"updated_at"`
}

func (harness sharedModulesHarness) createInteraction(t *testing.T, session string, body map[string]any) interactionResponse {
	t.Helper()
	recorder := harness.request(t, http.MethodPost, shared.Prefix+"/interactions", session, body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("create interaction status = %d (body %s)", recorder.Code, recorder.Body.String())
	}
	return sharedModulesDecode[interactionResponse](t, recorder)
}

func TestInteractionsCreateDerivesActorAndUniqueness(t *testing.T) {
	harness := newSharedModulesHarness(t)
	body := map[string]any{
		"target_type":      "care_plan",
		"target_id":        "plan-1",
		"interaction_type": "favorite",
		"value":            1.5,
		"payload":          map[string]any{"note": "x"},
	}
	created := harness.createInteraction(t, sharedModulesSubjectSession, body)
	sharedModulesRequireULID(t, created.ID)
	if created.ActorID != sharedModulesSubjectID {
		t.Fatalf("actor_id = %q, want %q", created.ActorID, sharedModulesSubjectID)
	}
	if created.Value == nil || *created.Value != 1.5 {
		t.Fatalf("value = %v, want 1.5", created.Value)
	}
	if created.TargetType != "care_plan" || created.TargetID != "plan-1" || created.InteractionType != "favorite" {
		t.Fatalf("created interaction = %#v", created)
	}
	sharedModulesRequireTimestamp(t, created.CreatedAt)
	sharedModulesRequireTimestamp(t, created.UpdatedAt)

	// value 缺失 -> JSON null；payload 缺失 -> {}。
	withoutOptionals := harness.request(t, http.MethodPost, shared.Prefix+"/interactions", sharedModulesSubjectSession, map[string]any{
		"target_type":      "care_plan",
		"target_id":        "plan-2",
		"interaction_type": "like",
	})
	if withoutOptionals.Code != http.StatusCreated {
		t.Fatalf("create without optionals status = %d (body %s)", withoutOptionals.Code, withoutOptionals.Body.String())
	}
	raw := sharedModulesDecode[map[string]json.RawMessage](t, withoutOptionals)
	if value, ok := raw["value"]; !ok || string(value) != "null" {
		t.Fatalf("value field = %s (present %v), want null", value, ok)
	}
	var payload map[string]any
	if err := json.Unmarshal(raw["payload"], &payload); err != nil || len(payload) != 0 {
		t.Fatalf("default payload = %s, want {}", raw["payload"])
	}

	// 四元组 UNIQUE 冲突 -> 409。
	duplicate := harness.request(t, http.MethodPost, shared.Prefix+"/interactions", sharedModulesSubjectSession, body)
	sharedModulesRequireProblem(t, duplicate, http.StatusConflict, "conflict", "this interaction already exists for the current actor and target")

	// 换 interaction_type 或 target_id 均可再建。
	harness.createInteraction(t, sharedModulesSubjectSession, map[string]any{
		"target_type":      "care_plan",
		"target_id":        "plan-1",
		"interaction_type": "like",
	})
	harness.createInteraction(t, sharedModulesSubjectSession, map[string]any{
		"target_type":      "care_plan",
		"target_id":        "plan-3",
		"interaction_type": "favorite",
	})
	// 同一目标换 actor 也可再建。
	harness.createInteraction(t, sharedModulesOtherSession, body)

	// 校验：必填字段与 payload 对象。
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPost, shared.Prefix+"/interactions", sharedModulesSubjectSession, map[string]any{
		"target_type":      " ",
		"target_id":        "plan-9",
		"interaction_type": "favorite",
	}), http.StatusBadRequest, "invalid-request", "target_type, target_id, and interaction_type are required")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPost, shared.Prefix+"/interactions", sharedModulesSubjectSession, map[string]any{
		"target_type":      "care_plan",
		"target_id":        "plan-9",
		"interaction_type": "favorite",
		"payload":          []any{1},
	}), http.StatusBadRequest, "invalid-request", "payload must be a JSON object")

	// 无会话 -> 401；尾斜杠兼容。
	sharedModulesRequireProblem(t, harness.request(t, http.MethodGet, shared.Prefix+"/interactions", "", nil), http.StatusUnauthorized, "not-authenticated", "not authenticated")
	trailing := harness.request(t, http.MethodPost, shared.Prefix+"/interactions/", sharedModulesSubjectSession, map[string]any{
		"target_type":      "care_plan",
		"target_id":        "plan-10",
		"interaction_type": "follow",
	})
	if trailing.Code != http.StatusCreated {
		t.Fatalf("trailing-slash create status = %d (body %s)", trailing.Code, trailing.Body.String())
	}
}

func TestInteractionsListFiltersOrderingAndPagination(t *testing.T) {
	harness := newSharedModulesHarness(t)
	seed := func(index int, actor, targetType, targetID, interactionType, createdAt string) string {
		t.Helper()
		id := seedID("01J", index)
		harness.seed(t, `
			INSERT INTO interactions (id, app_code, actor_id, target_type, target_id, interaction_type, value, payload, created_at, updated_at)
			VALUES (?, 'aceso', ?, ?, ?, ?, 0.5, '{"seed":true}', ?, ?)`,
			id, actor, targetType, targetID, interactionType, createdAt, createdAt)
		return id
	}
	oldest := seed(1, sharedModulesSubjectID, "care_plan", "plan-a", "like", "2026-01-01T00:00:01.000Z")
	middle := seed(2, sharedModulesSubjectID, "care_plan", "plan-b", "favorite", "2026-01-01T00:00:02.000Z")
	newest := seed(3, sharedModulesOtherSubjectID, "post", "post-1", "like", "2026-01-01T00:00:03.000Z")

	listIDs := func(query string) []string {
		t.Helper()
		recorder := harness.request(t, http.MethodGet, shared.Prefix+"/interactions"+query, sharedModulesSubjectSession, nil)
		if recorder.Code != http.StatusOK {
			t.Fatalf("list interactions%s status = %d (body %s)", query, recorder.Code, recorder.Body.String())
		}
		raw := sharedModulesDecodeArray(t, recorder)
		ids := make([]string, 0, len(raw))
		for _, value := range raw {
			var interaction interactionResponse
			if err := json.Unmarshal(value, &interaction); err != nil {
				t.Fatalf("decode listed interaction: %v", err)
			}
			ids = append(ids, interaction.ID)
		}
		return ids
	}

	// 列表不按 actor 自动收窄（Nexus 语义）；ORDER BY created_at DESC。
	all := listIDs("")
	if len(all) != 3 || all[0] != newest || all[1] != middle || all[2] != oldest {
		t.Fatalf("interactions order = %v, want [%s %s %s]", all, newest, middle, oldest)
	}
	if got := listIDs("?actor_id=" + sharedModulesSubjectID); len(got) != 2 {
		t.Fatalf("actor_id filter length = %d, want 2", len(got))
	}
	if got := listIDs("?target_type=care_plan"); len(got) != 2 {
		t.Fatalf("target_type filter length = %d, want 2", len(got))
	}
	if got := listIDs("?target_id=plan-b"); len(got) != 1 || got[0] != middle {
		t.Fatalf("target_id filter = %v, want [%s]", got, middle)
	}
	if got := listIDs("?interaction_type=favorite"); len(got) != 1 || got[0] != middle {
		t.Fatalf("interaction_type filter = %v, want [%s]", got, middle)
	}
	// 分页作用在排序结果上。
	if got := listIDs("?page=2&page_size=1"); len(got) != 1 || got[0] != middle {
		t.Fatalf("page 2 = %v, want [%s]", got, middle)
	}
	sharedModulesRequireProblem(t, harness.request(t, http.MethodGet, shared.Prefix+"/interactions?page=0", sharedModulesSubjectSession, nil), http.StatusBadRequest, "invalid-request", "page must be positive and page_size must be between 1 and 100")
}

func TestInteractionsOwnershipUpdateAndDelete(t *testing.T) {
	harness := newSharedModulesHarness(t)
	created := harness.createInteraction(t, sharedModulesSubjectSession, map[string]any{
		"target_type":      "care_plan",
		"target_id":        "plan-1",
		"interaction_type": "favorite",
		"value":            2,
		"payload":          map[string]any{"note": "original"},
	})

	// 读取对任意会话开放；改/删只允许 actor 本人，他人一律 404（Nexus 语义）。
	if recorder := harness.request(t, http.MethodGet, shared.Prefix+"/interactions/"+created.ID, sharedModulesOtherSession, nil); recorder.Code != http.StatusOK {
		t.Fatalf("other actor read status = %d (body %s)", recorder.Code, recorder.Body.String())
	}
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPut, shared.Prefix+"/interactions/"+created.ID, sharedModulesOtherSession, map[string]any{
		"value": 9,
	}), http.StatusNotFound, "not-found", "interaction not found")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodDelete, shared.Prefix+"/interactions/"+created.ID, sharedModulesOtherSession, nil), http.StatusNotFound, "not-found", "interaction not found")

	// value = 0 是合法值，不能被当成“未提供”。
	zeroValue := sharedModulesDecode[interactionResponse](t, harness.request(t, http.MethodPut, shared.Prefix+"/interactions/"+created.ID, sharedModulesSubjectSession, map[string]any{
		"value": 0,
	}))
	if zeroValue.Value == nil || *zeroValue.Value != 0 {
		t.Fatalf("value = %v, want 0", zeroValue.Value)
	}
	if zeroValue.Payload["note"] != "original" {
		t.Fatalf("payload changed by value-only update: %v", zeroValue.Payload)
	}

	// 只给 payload：value 保持上一步的 0。
	payloadOnly := sharedModulesDecode[interactionResponse](t, harness.request(t, http.MethodPut, shared.Prefix+"/interactions/"+created.ID, sharedModulesSubjectSession, map[string]any{
		"payload": map[string]any{"note": "updated"},
	}))
	if payloadOnly.Payload["note"] != "updated" || payloadOnly.Value == nil || *payloadOnly.Value != 0 {
		t.Fatalf("payload-only update = %#v", payloadOnly)
	}
	sharedModulesRequireTimestamp(t, payloadOnly.UpdatedAt)

	sharedModulesRequireProblem(t, harness.request(t, http.MethodPut, shared.Prefix+"/interactions/"+created.ID, sharedModulesSubjectSession, map[string]any{}), http.StatusBadRequest, "invalid-request", "value or payload is required")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPut, shared.Prefix+"/interactions/"+created.ID, sharedModulesSubjectSession, map[string]any{"payload": "text"}), http.StatusBadRequest, "invalid-request", "payload must be a JSON object")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPut, shared.Prefix+"/interactions/not-a-real-id", sharedModulesSubjectSession, map[string]any{"value": 1}), http.StatusNotFound, "not-found", "interaction not found")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodGet, shared.Prefix+"/interactions/not-a-real-id", sharedModulesSubjectSession, nil), http.StatusNotFound, "not-found", "interaction not found")

	// 本人 DELETE -> 204，再查/再删 -> 404。
	if recorder := harness.request(t, http.MethodDelete, shared.Prefix+"/interactions/"+created.ID, sharedModulesSubjectSession, nil); recorder.Code != http.StatusNoContent {
		t.Fatalf("delete status = %d (body %s)", recorder.Code, recorder.Body.String())
	}
	sharedModulesRequireProblem(t, harness.request(t, http.MethodGet, shared.Prefix+"/interactions/"+created.ID, sharedModulesSubjectSession, nil), http.StatusNotFound, "not-found", "interaction not found")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodDelete, shared.Prefix+"/interactions/"+created.ID, sharedModulesSubjectSession, nil), http.StatusNotFound, "not-found", "interaction not found")

	empty := harness.request(t, http.MethodGet, shared.Prefix+"/interactions", sharedModulesSubjectSession, nil)
	if strings.TrimSpace(empty.Body.String()) != "[]" {
		t.Fatalf("empty interactions body = %q, want []", empty.Body.String())
	}
}
