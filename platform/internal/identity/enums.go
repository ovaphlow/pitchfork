package identity

// 本文件是 identity 域内所有枚举值的唯一来源，与 db/migrations 中的 CHECK 约束
// 一一对应。修改任一枚举值必须同步修改迁移文件，enums_test.go 会双向校验
// 常量集合与迁移字面量集合一致。
//
// 注意：identity_subjects.status 与 identity_identifiers.status 共用同一枚举域，
// 因此只定义一组 Status* 常量。

// status：identity_subjects.status、identity_identifiers.status。
const (
	StatusEnabled  = "启用"
	StatusDisabled = "禁用"
)

// identifier_type：identity_identifiers.identifier_type。
const (
	IdentifierTypeAccount = "账号"
	IdentifierTypeEmail   = "邮箱"
	IdentifierTypeMobile  = "手机号"
	IdentifierTypeStaff   = "工号"
)

// identifier_usage：identity_identifiers.identifier_usage。
const (
	IdentifierUsagePrimaryLogin   = "主登录"
	IdentifierUsageSecondaryLogin = "辅助登录"
	IdentifierUsageContact        = "联系"
)

// credential_status：identity_password_credentials.credential_status。
const (
	CredentialStatusValid      = "有效"
	CredentialStatusMustUpdate = "需更新"
	CredentialStatusVoided     = "已作废"
)

// session_access：identity_sessions.session_access。
const (
	SessionAccessFull         = "完整"
	SessionAccessPasswordOnly = "仅改密"
)

// revoked_reason：identity_sessions.revoked_reason。
const (
	RevokedReasonUserLogout           = "用户退出"
	RevokedReasonSubjectDisabled      = "主体禁用"
	RevokedReasonCredentialChanged    = "凭据变更"
	RevokedReasonPermissionRevoked    = "权限收回"
	RevokedReasonAdministratorRevoked = "管理员撤销"
)

// event_action：identity_audit_events.event_action。
const (
	AuditActionLogin                 = "登录"
	AuditActionLogout                = "退出登录"
	AuditActionSubjectCreated        = "主体创建"
	AuditActionSubjectStatusChanged  = "主体状态变更"
	AuditActionIdentifierChanged     = "标识符变更"
	AuditActionCredentialChanged     = "凭据变更"
	AuditActionRoleGranted           = "角色授予"
	AuditActionRoleRevoked           = "角色撤销"
	AuditActionSessionRevoked        = "会话撤销"
	AuditActionAdministratorRestored = "管理员恢复"
	AuditActionMaintenanceCleanup    = "维护清理"
)

// outcome：identity_audit_events.outcome。
const (
	OutcomeSucceeded = "成功"
	OutcomeFailed    = "失败"
)

// 039：IDP 不再持有控制面角色目录。管理台准入改判 identity_subjects.is_platform_admin
// （见 db/migrations/000011_platform_admin.sql）；产品角色与部门归 Nexus。
