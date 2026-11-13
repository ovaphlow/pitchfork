package httpapi

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/agora-api/internal/apierr"
	"github.com/ovaphlow/pitchfork/agora-api/internal/organizations"
	"github.com/ovaphlow/pitchfork/agora-api/internal/positions"
)

// PositionStore is the job-posting capability the HTTP layer depends on.
type PositionStore interface {
	Create(ctx context.Context, input positions.CreateInput, actorSubjectID string) (positions.Position, error)
	Get(ctx context.Context, positionID string) (positions.Position, error)
	IncreaseViewCount(ctx context.Context, positionID string) (int64, error)
	List(ctx context.Context, filter positions.Filter) ([]positions.Position, int64, error)
	ListByOrganization(ctx context.Context, organizationID string, limit, offset int32) ([]positions.Position, int64, error)
	Update(ctx context.Context, positionID string, input positions.UpdateInput) (positions.Position, error)
	SetStatus(ctx context.Context, positionID string, next positions.Status) (positions.Position, error)
	Delete(ctx context.Context, positionID string) error
}

func (deps Deps) listPositions(responseWriter http.ResponseWriter, request *http.Request) {
	limit, offset, err := parsePagination(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	minimumSalary, err := queryInt(request, "salary_min", 0)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	query := request.URL.Query()
	records, total, err := deps.Positions.List(request.Context(), positions.Filter{
		OrganizationID: strings.TrimSpace(query.Get("organization_id")),
		City:           strings.TrimSpace(query.Get("city")),
		Category:       strings.TrimSpace(query.Get("category")),
		EmploymentType: strings.TrimSpace(query.Get("employment_type")),
		Education:      strings.TrimSpace(query.Get("education")),
		Experience:     strings.TrimSpace(query.Get("experience")),
		MinimumSalary:  minimumSalary,
		Query:          strings.TrimSpace(query.Get("q")),
		Limit:          limit,
		Offset:         offset,
	})
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	writeList(responseWriter, http.StatusOK, records, total)
}

func (deps Deps) createPosition(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	var input positions.CreateInput
	if err := decodeBody(responseWriter, request, &input); err != nil {
		writeError(responseWriter, http.StatusBadRequest, "请求格式无效")
		return
	}
	input, err = input.Validate()
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	if _, err := deps.requireRole(request.Context(), input.OrganizationID, identity.SubjectID, organizations.Role.CanManagePositions); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	if err := deps.requireUsableOrganization(request.Context(), input.OrganizationID); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	position, err := deps.Positions.Create(request.Context(), input, identity.SubjectID)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	writeJSON(responseWriter, http.StatusCreated, position)
}

func (deps Deps) getPosition(responseWriter http.ResponseWriter, request *http.Request) {
	position, err := deps.Positions.Get(request.Context(), request.PathValue("id"))
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	if !position.Public(time.Now().UTC()) {
		identity, ok := deps.optionalIdentity(request)
		if !ok {
			writeError(responseWriter, http.StatusNotFound, apierr.MessageNotFound)
			return
		}
		if _, err := deps.requireRole(request.Context(), position.OrganizationID, identity.SubjectID, organizations.Role.CanManagePositions); err != nil {
			writeError(responseWriter, http.StatusNotFound, apierr.MessageNotFound)
			return
		}
	} else if views, err := deps.Positions.IncreaseViewCount(request.Context(), position.ID); err == nil {
		position.ViewCount = views
	}

	writeJSON(responseWriter, http.StatusOK, position)
}

func (deps Deps) updatePosition(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	position, err := deps.Positions.Get(request.Context(), request.PathValue("id"))
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	if _, err := deps.requireRole(request.Context(), position.OrganizationID, identity.SubjectID, organizations.Role.CanManagePositions); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	var input positions.UpdateInput
	if err := decodeBody(responseWriter, request, &input); err != nil {
		writeError(responseWriter, http.StatusBadRequest, "请求格式无效")
		return
	}
	input, err = input.Validate()
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	updated, err := deps.Positions.Update(request.Context(), position.ID, input)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	writeJSON(responseWriter, http.StatusOK, updated)
}

func (deps Deps) setPositionStatus(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	current, err := deps.Positions.Get(request.Context(), request.PathValue("id"))
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	if _, err := deps.requireRole(request.Context(), current.OrganizationID, identity.SubjectID, organizations.Role.CanManagePositions); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	var input positions.StatusInput
	if err := decodeBody(responseWriter, request, &input); err != nil {
		writeError(responseWriter, http.StatusBadRequest, "请求格式无效")
		return
	}
	next, err := input.Validate(current.Status)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	// 上线要求机构已认证；草稿可以停留在未认证的机构里。
	if next == positions.StatusOpen {
		if err := deps.requireVerifiedOrganization(request.Context(), current.OrganizationID); err != nil {
			deps.writeStoreError(responseWriter, request, err)
			return
		}
	}

	updated, err := deps.Positions.SetStatus(request.Context(), current.ID, next)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	writeJSON(responseWriter, http.StatusOK, updated)
}

func (deps Deps) deletePosition(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	position, err := deps.Positions.Get(request.Context(), request.PathValue("id"))
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	role, err := deps.requireRole(request.Context(), position.OrganizationID, identity.SubjectID, organizations.Role.CanManagePositions)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	// 招聘专员只能删除自己创建的岗位。
	if role == organizations.RoleRecruiter && position.CreatedBy != identity.SubjectID {
		writeError(responseWriter, http.StatusForbidden, "只能删除自己创建的岗位")
		return
	}

	if err := deps.Positions.Delete(request.Context(), position.ID); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	responseWriter.WriteHeader(http.StatusNoContent)
}

func (deps Deps) listOrganizationPositions(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	organizationID := request.PathValue("id")
	if _, err := deps.requireRole(request.Context(), organizationID, identity.SubjectID, organizations.Role.CanManagePositions); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	limit, offset, err := parsePagination(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	records, total, err := deps.Positions.ListByOrganization(request.Context(), organizationID, limit, offset)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	writeList(responseWriter, http.StatusOK, records, total)
}

// requireVerifiedOrganization rejects publishing for organisations that have not
// passed review.
func (deps Deps) requireVerifiedOrganization(ctx context.Context, organizationID string) error {
	status, err := deps.Organizations.Status(ctx, organizationID)
	if err != nil {
		return err
	}
	if status != organizations.StatusVerified {
		return &apierr.Forbidden{Message: "机构尚未通过认证，无法发布岗位"}
	}
	return nil
}

// requireUsableOrganization rejects drafting inside a rejected or disabled
// organisation.
func (deps Deps) requireUsableOrganization(ctx context.Context, organizationID string) error {
	status, err := deps.Organizations.Status(ctx, organizationID)
	if err != nil {
		return err
	}
	switch status {
	case organizations.StatusRejected, organizations.StatusDisabled:
		return &apierr.Forbidden{Message: "机构当前状态无法维护岗位"}
	}
	return nil
}
