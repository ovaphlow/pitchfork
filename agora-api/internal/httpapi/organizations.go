package httpapi

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/ovaphlow/pitchfork/agora-api/internal/apierr"
	"github.com/ovaphlow/pitchfork/agora-api/internal/organizations"
)

// OrganizationStore is the organisation capability the HTTP layer depends on.
type OrganizationStore interface {
	Create(ctx context.Context, input organizations.CreateInput, ownerSubjectID string) (organizations.Organization, error)
	Get(ctx context.Context, organizationID string) (organizations.Organization, error)
	Status(ctx context.Context, organizationID string) (organizations.Status, error)
	Update(ctx context.Context, organizationID string, input organizations.UpdateInput) (organizations.Organization, error)
	Review(ctx context.Context, organizationID string, input organizations.ReviewInput, reviewer string) (organizations.Organization, error)
	List(ctx context.Context, filter organizations.Filter) ([]organizations.Organization, int64, error)
	ListForSubject(ctx context.Context, subjectID string) ([]organizations.Membership, error)
	Role(ctx context.Context, organizationID, subjectID string) (organizations.Role, error)
	Members(ctx context.Context, organizationID string) ([]organizations.Member, error)
	AddMember(ctx context.Context, organizationID, subjectID string, role organizations.Role) (organizations.Member, error)
	RemoveMember(ctx context.Context, organizationID, subjectID string) error
}

type memberInput struct {
	SubjectID  string `json:"subject_id"`
	MemberRole string `json:"member_role"`
}

func (deps Deps) listOrganizations(responseWriter http.ResponseWriter, request *http.Request) {
	limit, offset, err := parsePagination(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	records, total, err := deps.Organizations.List(request.Context(), organizations.Filter{
		City:    strings.TrimSpace(request.URL.Query().Get("city")),
		OrgType: strings.TrimSpace(request.URL.Query().Get("org_type")),
		Query:   strings.TrimSpace(request.URL.Query().Get("q")),
		Limit:   limit,
		Offset:  offset,
	})
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	writeList(responseWriter, http.StatusOK, records, total)
}

func (deps Deps) createOrganization(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	var input organizations.CreateInput
	if err := decodeBody(responseWriter, request, &input); err != nil {
		writeError(responseWriter, http.StatusBadRequest, "请求格式无效")
		return
	}
	input, err = input.Validate()
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	organization, err := deps.Organizations.Create(request.Context(), input, identity.SubjectID)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	writeJSON(responseWriter, http.StatusCreated, organization)
}

func (deps Deps) listMyOrganizations(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	memberships, err := deps.Organizations.ListForSubject(request.Context(), identity.SubjectID)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	writeList(responseWriter, http.StatusOK, memberships, int64(len(memberships)))
}

func (deps Deps) getOrganization(responseWriter http.ResponseWriter, request *http.Request) {
	organizationID := request.PathValue("id")

	organization, err := deps.Organizations.Get(request.Context(), organizationID)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	// Only verified organisations are public; members may always read their own.
	if organization.Status != organizations.StatusVerified {
		identity, ok := deps.optionalIdentity(request)
		if !ok {
			writeError(responseWriter, http.StatusNotFound, apierr.MessageNotFound)
			return
		}
		if _, err := deps.requireRole(request.Context(), organizationID, identity.SubjectID, organizations.Role.CanManagePositions); err != nil {
			writeError(responseWriter, http.StatusNotFound, apierr.MessageNotFound)
			return
		}
	}
	writeJSON(responseWriter, http.StatusOK, organization)
}

func (deps Deps) updateOrganization(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	organizationID := request.PathValue("id")
	if _, err := deps.requireRole(request.Context(), organizationID, identity.SubjectID, organizations.Role.CanEditProfile); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	var input organizations.UpdateInput
	if err := decodeBody(responseWriter, request, &input); err != nil {
		writeError(responseWriter, http.StatusBadRequest, "请求格式无效")
		return
	}
	input, err = input.Validate()
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	organization, err := deps.Organizations.Update(request.Context(), organizationID, input)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	writeJSON(responseWriter, http.StatusOK, organization)
}

func (deps Deps) reviewOrganization(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	if !identity.PlatformAdmin {
		writeError(responseWriter, http.StatusForbidden, "只有平台管理员可以审核机构")
		return
	}

	var input organizations.ReviewInput
	if err := decodeBody(responseWriter, request, &input); err != nil {
		writeError(responseWriter, http.StatusBadRequest, "请求格式无效")
		return
	}
	input, err = input.Validate()
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	organization, err := deps.Organizations.Review(request.Context(), request.PathValue("id"), input, identity.SubjectID)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	writeJSON(responseWriter, http.StatusOK, organization)
}

func (deps Deps) listOrganizationMembers(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	organizationID := request.PathValue("id")
	if _, err := deps.requireRole(request.Context(), organizationID, identity.SubjectID, organizations.Role.CanManageMembers); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	members, err := deps.Organizations.Members(request.Context(), organizationID)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	writeList(responseWriter, http.StatusOK, members, int64(len(members)))
}

func (deps Deps) addOrganizationMember(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	organizationID := request.PathValue("id")
	if _, err := deps.requireRole(request.Context(), organizationID, identity.SubjectID, organizations.Role.CanManageMembers); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	var input memberInput
	if err := decodeBody(responseWriter, request, &input); err != nil {
		writeError(responseWriter, http.StatusBadRequest, "请求格式无效")
		return
	}

	subjectID := strings.TrimSpace(input.SubjectID)
	if subjectID == "" || len(subjectID) > 64 {
		writeError(responseWriter, http.StatusBadRequest, "成员主体标识无效")
		return
	}
	role, err := organizations.ValidateMemberRole(organizations.Role(strings.TrimSpace(input.MemberRole)))
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	member, err := deps.Organizations.AddMember(request.Context(), organizationID, subjectID, role)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	writeJSON(responseWriter, http.StatusCreated, member)
}

func (deps Deps) removeOrganizationMember(responseWriter http.ResponseWriter, request *http.Request) {
	identity, err := deps.authenticate(request)
	if err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	organizationID := request.PathValue("id")
	if _, err := deps.requireRole(request.Context(), organizationID, identity.SubjectID, organizations.Role.CanManageMembers); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}

	if err := deps.Organizations.RemoveMember(request.Context(), organizationID, request.PathValue("subject_id")); err != nil {
		deps.writeStoreError(responseWriter, request, err)
		return
	}
	responseWriter.WriteHeader(http.StatusNoContent)
}

// requireRole resolves the caller's membership and checks it against a role
// capability. Non-members see 403, never 404, because the organisation itself is
// public.
func (deps Deps) requireRole(ctx context.Context, organizationID, subjectID string, allow func(organizations.Role) bool) (organizations.Role, error) {
	role, err := deps.Organizations.Role(ctx, organizationID, subjectID)
	if err != nil {
		if errors.Is(err, organizations.ErrNotMember) {
			return "", &apierr.Forbidden{Message: "你不是该机构成员"}
		}
		return "", err
	}
	if !allow(role) {
		return "", &apierr.Forbidden{Message: "当前角色无权执行该操作"}
	}
	return role, nil
}
