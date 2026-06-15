import { JSEncrypt } from "jsencrypt";

const API_BASE =
	import.meta.env.PUBLIC_API_URL ?? "http://192.168.0.109:8421/crate-api";

const TOKEN_KEY = "auth_token";
const PUBLIC_KEY_CACHE_KEY = "auth_public_key";

export interface User {
	id: string;
	email: string;
	username: string;
	phone: string;
	user_type: string;
	status: string;
	created_at: string;
	updated_at: string;
}

export interface LoginResponse {
	token: string;
	type: string;
	user: User;
}

export interface SignUpResponse {
	status: string;
	user: User;
}

export interface ApiError {
	error: string;
}

/** 从 localStorage 获取 token */
export function getToken(): string | null {
	if (typeof window === "undefined") return null;
	return localStorage.getItem(TOKEN_KEY);
}

/** 将 token 持久化到 localStorage */
export function setToken(token: string): void {
	localStorage.setItem(TOKEN_KEY, token);
}

/** 清除 token（登出时调用） */
export function clearToken(): void {
	localStorage.removeItem(TOKEN_KEY);
}

/** 清除缓存的 RSA 公钥 */
export function clearPublicKeyCache(): void {
	localStorage.removeItem(PUBLIC_KEY_CACHE_KEY);
}

/** 获取后端 RSA 公钥（Base64 X.509 SubjectPublicKeyInfo） */
async function fetchPublicKeyBase64(): Promise<string> {
	const cached = localStorage.getItem(PUBLIC_KEY_CACHE_KEY);
	if (cached) return cached;

	const res = await fetch(`${API_BASE}/auth/v1/public-key`);
	if (!res.ok) throw new Error("无法获取加密密钥");
	const { publicKey } = await res.json();
	localStorage.setItem(PUBLIC_KEY_CACHE_KEY, publicKey);
	return publicKey;
}

/** 用 RSA 公钥加密密码，返回 Base64 密文 */
async function encryptPassword(password: string): Promise<string> {
	const encryptor = new JSEncrypt();
	encryptor.setPublicKey(await fetchPublicKeyBase64());
	const encrypted = encryptor.encrypt(password);
	if (!encrypted) throw new Error("密码加密失败");
	return encrypted;
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
	const url = `${API_BASE}${path}`;
	const { headers: optHeaders, ...rest } = options;

	const headers: Record<string, string> = {
		"Content-Type": "application/json",
		...(optHeaders as Record<string, string>),
	};

	// 自动注入 Authorization header
	const token = getToken();
	if (token) {
		headers["Authorization"] = `Bearer ${token}`;
	}

	let res: Response;
	try {
		res = await fetch(url, {
			...rest,
			headers,
		});
	} catch (err) {
		console.error("[api] fetch error:", url, err instanceof Error ? err.message : err);
		throw new Error(`无法连接到服务器，请检查网络或后端是否运行中`);
	}

	// token 过期/无效 → 清除并跳转登录
	if (res.status === 401) {
		clearToken();
		if (typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
			window.location.href = "/login";
		}
		throw new Error("登录已过期，请重新登录");
	}

	let data: unknown;
	try {
		data = await res.json();
	} catch {
		data = null;
	}

	if (!res.ok) {
		const message = (data as ApiError | null)?.error ?? `Request failed (${res.status})`;
		console.error("[api]", res.status, url, message);
		throw new Error(message);
	}

	return data as T;
}

async function encryptedPost<T>(
	path: string,
	email: string,
	password: string,
	retries = 1,
): Promise<T> {
	const encryptedPassword = await encryptPassword(password);
	try {
		return await request<T>(path, {
			method: "POST",
			body: JSON.stringify({ email, password: encryptedPassword }),
		});
	} catch (err) {
		// 密码解密失败 → 可能服务器重启了，公钥已变 → 清除缓存重试一次
		if (
			retries > 0 &&
			err instanceof Error &&
			err.message === "password decryption failed"
		) {
			localStorage.removeItem(PUBLIC_KEY_CACHE_KEY);
			return encryptedPost<T>(path, email, password, retries - 1);
		}
		throw err;
	}
}

export async function login(
	email: string,
	password: string,
): Promise<LoginResponse> {
	return encryptedPost<LoginResponse>("/auth/v1/login", email, password);
}

export async function signUp(
	email: string,
	password: string,
): Promise<SignUpResponse> {
	return encryptedPost<SignUpResponse>("/auth/v1/sign-up", email, password);
}

export async function verify(
	token: string,
): Promise<{ valid: boolean; sub: string }> {
	return request("/auth/v1/verify", {
		method: "GET",
		headers: { Authorization: `Bearer ${token}` },
	});
}

// ---- Settings API (knowledge categories & tags) ----

export async function listKnowledgeCategorySettings(): Promise<{ code: string; name: string }[]> {
  const res: { records: { code: string; payload: { name: string } }[] } = await request("/settings/v1/knowledge-categories");
  return res.records.map((r) => ({ code: r.code, name: r.payload?.name ?? r.code }));
}

export async function createKnowledgeCategorySetting(data: { code: string; name: string }): Promise<unknown> {
  return request("/settings/v1/knowledge-categories", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function updateKnowledgeCategorySetting(code: string, data: { name: string }): Promise<unknown> {
  return request(`/settings/v1/knowledge-categories/${code}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export async function deleteKnowledgeCategorySetting(code: string): Promise<void> {
  await request(`/settings/v1/knowledge-categories/${code}`, { method: "DELETE" });
}

export async function listKnowledgeTagSettings(): Promise<string[]> {
  const res: { records: { code: string; payload: { name: string } }[] } = await request("/settings/v1/knowledge-tags");
  return res.records.map((r) => r.payload?.name ?? r.code);
}

export async function createKnowledgeTagSetting(data: { code: string; name: string }): Promise<unknown> {
  return request("/settings/v1/knowledge-tags", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function deleteKnowledgeTagSetting(code: string): Promise<void> {
  await request(`/settings/v1/knowledge-tags/${code}`, { method: "DELETE" });
}

export async function listDepartments(): Promise<unknown[]> {
	return request("/settings/v1/departments");
}

export async function createDepartment(data: {
	name: string;
	code: string;
	parent_code?: string;
	description?: string;
}): Promise<unknown> {
	return request("/settings/v1/departments", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function updateDepartment(
	id: string,
	data: {
		name?: string;
		code?: string;
		parent_code?: string;
		description?: string;
	},
): Promise<unknown> {
	return request(`/settings/v1/departments/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export async function deleteDepartment(id: string): Promise<void> {
	await request(`/settings/v1/departments/${id}`, { method: "DELETE" });
}

// ---- Users ----

export async function listUsers(params?: {
	search?: string;
	status?: string;
	limit?: number;
	offset?: number;
}): Promise<unknown[]> {
	const q = new URLSearchParams();
	if (params?.search) q.set("search", params.search);
	if (params?.status) q.set("status", params.status);
	if (params?.limit) q.set("limit", String(params.limit));
	if (params?.offset) q.set("offset", String(params.offset));
	const qs = q.toString();
	return request(`/users/v1/users${qs ? "?" + qs : ""}`);
}

export async function updateUserStatus(
	id: string,
	status: string,
): Promise<unknown> {
	return request(`/users/v1/users/${id}/status`, {
		method: "PATCH",
		body: JSON.stringify({ status }),
	});
}

// ---- Roles & Permissions ----

export async function listRoles(): Promise<{ id: string; name: string; code: string; description?: string }[]> {
	return request("/permission/v1/roles");
}

export async function assignRole(userId: string, roleId: string): Promise<unknown> {
	return request("/permission/v1/assignments", {
		method: "POST",
		body: JSON.stringify({ user_id: userId, role_id: roleId }),
	});
}

export async function unassignRole(userId: string, roleId: string): Promise<unknown> {
	return request(`/permission/v1/assignments?user_id=${encodeURIComponent(userId)}&role_id=${encodeURIComponent(roleId)}`, {
		method: "DELETE",
	});
}

export async function getUserAssignments(userId: string): Promise<{ role_id: string; role_name: string; scope_type: string; scope_id: string }[]> {
	return request(`/permission/v1/users/${userId}/assignments`);
}

// ═══════════════════════════════════════════
// Knowledge API
// ═══════════════════════════════════════════

export interface KnowledgeEntry {
	id: string;
	title: string;
	content?: string;
	type?: string;
	status?: string;
	category_ids?: string[];
	category_id?: string;
	category_name?: string;
	tags?: string[];
	version?: number;
	version_number?: number;
	author?: string;
	created_at?: string;
	updated_at?: string;
}

export interface KnowledgeCategory {
	id: string;
	name: string;
	parent_id?: string;
	sort_order?: number;
	created_at?: string;
}

export interface KnowledgeVersion {
	id: string;
	entry_id: string;
	version: number;
	content: string;
	status: string;
	created_by?: string;
	created_at?: string;
}

export interface KnowledgeFeedback {
	id: string;
	entry_id: string;
	content: string;
	reply?: string;
	created_by?: string;
	created_at?: string;
}

export async function listKnowledgeEntries(params?: {
	type?: string;
	status?: string;
	search?: string;
	category_id?: string;
	tags?: string[];
	limit?: number;
	offset?: number;
}): Promise<KnowledgeEntry[]> {
	const q = new URLSearchParams();
	if (params?.type) q.set("type", params.type);
	if (params?.status) q.set("status", params.status);
	if (params?.search) q.set("search", params.search);
	if (params?.category_id) q.set("category_id", params.category_id);
	if (params?.tags?.length) q.set("tags", params.tags.join(","));
	if (params?.limit) q.set("limit", String(params.limit));
	if (params?.offset) q.set("offset", String(params.offset));
	const qs = q.toString();
	const res: { records: KnowledgeEntry[]; total?: number } = await request(`/knowledge/v1/entries${qs ? "?" + qs : ""}`);
	return res.records;
}

export async function createKnowledgeEntry(data: Partial<KnowledgeEntry>): Promise<KnowledgeEntry> {
	return request("/knowledge/v1/entries", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function getKnowledgeEntry(id: string): Promise<KnowledgeEntry> {
	return request(`/knowledge/v1/entries/${id}`);
}

export async function updateKnowledgeEntry(id: string, data: Partial<KnowledgeEntry>): Promise<KnowledgeEntry> {
	return request(`/knowledge/v1/entries/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export async function deleteKnowledgeEntry(id: string): Promise<void> {
	await request(`/knowledge/v1/entries/${id}`, { method: "DELETE" });
}

export async function listKnowledgeVersions(entryId: string): Promise<KnowledgeVersion[]> {
	const res: { records: KnowledgeVersion[] } = await request(`/knowledge/v1/entries/${entryId}/versions`);
	return res.records;
}

export async function createKnowledgeVersion(entryId: string, data: { content: string; change_note?: string }): Promise<KnowledgeVersion> {
	return request(`/knowledge/v1/entries/${entryId}/versions`, {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function approveKnowledgeVersion(entryId: string, versionId: string): Promise<KnowledgeVersion> {
	return request(`/knowledge/v1/entries/${entryId}/versions/${versionId}/approve`, {
		method: "POST",
	});
}

export async function rejectKnowledgeVersion(entryId: string, versionId: string): Promise<KnowledgeVersion> {
	return request(`/knowledge/v1/entries/${entryId}/versions/${versionId}/reject`, {
		method: "POST",
	});
}

export async function listKnowledgeFeedbacks(entryId: string): Promise<KnowledgeFeedback[]> {
	const res: { records: KnowledgeFeedback[] } = await request(`/knowledge/v1/entries/${entryId}/feedbacks`);
	return res.records;
}

export async function createKnowledgeFeedback(entryId: string, data: { content: string }): Promise<KnowledgeFeedback> {
	return request(`/knowledge/v1/entries/${entryId}/feedbacks`, {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function replyKnowledgeFeedback(feedbackId: string, reply: string): Promise<KnowledgeFeedback> {
	return request(`/knowledge/v1/feedbacks/${feedbackId}/reply`, {
		method: "POST",
		body: JSON.stringify({ reply }),
	});
}

export async function listKnowledgeCategories(): Promise<KnowledgeCategory[]> {
	return request("/knowledge/v1/categories");
}

export async function createKnowledgeCategory(data: Partial<KnowledgeCategory>): Promise<KnowledgeCategory> {
	return request("/knowledge/v1/categories", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function updateKnowledgeCategory(id: string, data: Partial<KnowledgeCategory>): Promise<KnowledgeCategory> {
	return request(`/knowledge/v1/categories/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export async function deleteKnowledgeCategory(id: string): Promise<void> {
	await request(`/knowledge/v1/categories/${id}`, { method: "DELETE" });
}

// ═══════════════════════════════════════════
// Skills API
// ═══════════════════════════════════════════

export interface Skill {
	id: string;
	name: string;
	description?: string;
	category?: string;
	level?: string;
	created_at?: string;
}

export interface Position {
	id: string;
	name: string;
	code?: string;
	parent_code?: string;
	description?: string;
	skill_requirements?: Record<string, number>;
	children?: Position[];
	created_at?: string;
}

export interface EmployeeSkill {
	id: string;
	employee_id: string;
	skill_id: string;
	skill_name?: string;
	level?: number;
	assessed_by?: string;
	assessed_at?: string;
}

export interface Certificate {
	id: string;
	name: string;
	issuer?: string;
	valid_years?: number;
	created_at?: string;
}

export interface EmployeeCertificate {
	id: string;
	employee_id: string;
	certificate_id: string;
	certificate_name?: string;
	issued_at?: string;
	expires_at?: string;
}

export async function listSkills(params?: {
	category?: string;
	search?: string;
	limit?: number;
	offset?: number;
}): Promise<Skill[]> {
	const q = new URLSearchParams();
	if (params?.category) q.set("category", params.category);
	if (params?.search) q.set("search", params.search);
	if (params?.limit) q.set("limit", String(params.limit));
	if (params?.offset) q.set("offset", String(params.offset));
	const qs = q.toString();
	return request(`/skills/v1/skills${qs ? "?" + qs : ""}`);
}

export async function createSkill(data: Partial<Skill>): Promise<Skill> {
	return request("/skills/v1/skills", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function updateSkill(id: string, data: Partial<Skill>): Promise<Skill> {
	return request(`/skills/v1/skills/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export async function deleteSkill(id: string): Promise<void> {
	await request(`/skills/v1/skills/${id}`, { method: "DELETE" });
}

export async function listPositions(params?: {
	search?: string;
	limit?: number;
	offset?: number;
}): Promise<Position[]> {
	const q = new URLSearchParams();
	if (params?.search) q.set("search", params.search);
	if (params?.limit) q.set("limit", String(params.limit));
	if (params?.offset) q.set("offset", String(params.offset));
	const qs = q.toString();
	return request(`/skills/v1/positions${qs ? "?" + qs : ""}`);
}

export async function getPositionTree(): Promise<Position[]> {
	return request("/skills/v1/positions/tree");
}

export async function createPosition(data: Partial<Position>): Promise<Position> {
	return request("/skills/v1/positions", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function updatePosition(id: string, data: Partial<Position>): Promise<Position> {
	return request(`/skills/v1/positions/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export async function deletePosition(id: string): Promise<void> {
	await request(`/skills/v1/positions/${id}`, { method: "DELETE" });
}

export async function listEmployeeSkills(employeeId: string): Promise<EmployeeSkill[]> {
	return request(`/skills/v1/employees/${employeeId}/skills`);
}

export async function assessEmployeeSkill(id: string, data: { level: number; comment?: string }): Promise<EmployeeSkill> {
	return request(`/skills/v1/employee-skills/${id}/assess`, {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function listCertificates(params?: {
	search?: string;
	limit?: number;
	offset?: number;
}): Promise<Certificate[]> {
	const q = new URLSearchParams();
	if (params?.search) q.set("search", params.search);
	if (params?.limit) q.set("limit", String(params.limit));
	if (params?.offset) q.set("offset", String(params.offset));
	const qs = q.toString();
	return request(`/skills/v1/certificates${qs ? "?" + qs : ""}`);
}

export async function createCertificate(data: Partial<Certificate>): Promise<Certificate> {
	return request("/skills/v1/certificates", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function updateCertificate(id: string, data: Partial<Certificate>): Promise<Certificate> {
	return request(`/skills/v1/certificates/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export async function deleteCertificate(id: string): Promise<void> {
	await request(`/skills/v1/certificates/${id}`, { method: "DELETE" });
}

export async function listEmployeeCertificates(employeeId: string): Promise<EmployeeCertificate[]> {
	return request(`/skills/v1/employees/${employeeId}/certificates`);
}

export async function createEmployeeCertificate(employeeId: string, data: { certificate_id: string; issued_at?: string; expires_at?: string }): Promise<EmployeeCertificate> {
	return request(`/skills/v1/employees/${employeeId}/certificates`, {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function deleteEmployeeCertificate(employeeId: string, certificateId: string): Promise<void> {
	await request(`/skills/v1/employees/${employeeId}/certificates/${certificateId}`, { method: "DELETE" });
}

// ═══════════════════════════════════════════
// Training API
// ═══════════════════════════════════════════

export interface Course {
	id: string;
	title: string;
	type?: string;
	description?: string;
	category?: string;
	difficulty?: string;
	duration?: number;
	cover_url?: string;
	status?: string;
	created_by?: string;
	created_at?: string;
	updated_at?: string;
}

export interface Chapter {
	id: string;
	course_id: string;
	title: string;
	content?: string;
	content_type?: string;
	sort_order?: number;
	duration?: number;
	created_at?: string;
}

export interface TrainingAssignment {
	id: string;
	course_id: string;
	course_title?: string;
	employee_id: string;
	employee_name?: string;
	assigned_by?: string;
	assigned_at?: string;
	deadline?: string;
	status?: string;
	progress?: number;
}

export interface LearningProgress {
	id: string;
	assignment_id: string;
	employee_id: string;
	chapter_id: string;
	completed: boolean;
	completed_at?: string;
}

export async function listCourses(params?: {
	category?: string;
	status?: string;
	search?: string;
	difficulty?: string;
	limit?: number;
	offset?: number;
}): Promise<Course[]> {
	const q = new URLSearchParams();
	if (params?.category) q.set("category", params.category);
	if (params?.status) q.set("status", params.status);
	if (params?.search) q.set("search", params.search);
	if (params?.difficulty) q.set("difficulty", params.difficulty);
	if (params?.limit) q.set("limit", String(params.limit));
	if (params?.offset) q.set("offset", String(params.offset));
	const qs = q.toString();
	const res: { records: Course[]; total?: number } = await request(`/training/v1/courses${qs ? "?" + qs : ""}`);
	return res.records;
}

export async function createCourse(data: Partial<Course>): Promise<Course> {
	return request("/training/v1/courses", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function updateCourse(id: string, data: Partial<Course>): Promise<Course> {
	return request(`/training/v1/courses/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export async function deleteCourse(id: string): Promise<void> {
	await request(`/training/v1/courses/${id}`, { method: "DELETE" });
}

export async function listChapters(courseId: string): Promise<Chapter[]> {
	const res: { records: Chapter[] } = await request(`/training/v1/courses/${courseId}/chapters`);
	return res.records;
}

export async function createChapter(courseId: string, data: Partial<Chapter>): Promise<Chapter> {
	return request(`/training/v1/courses/${courseId}/chapters`, {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function updateChapter(id: string, data: Partial<Chapter>): Promise<Chapter> {
	return request(`/training/v1/chapters/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export async function deleteChapter(id: string): Promise<void> {
	await request(`/training/v1/chapters/${id}`, { method: "DELETE" });
}

export async function createTrainingAssignment(data: { course_id: string; employee_ids: string[]; deadline?: string }): Promise<TrainingAssignment> {
	return request("/training/v1/assignments", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function listTrainingAssignments(params?: {
	course_id?: string;
	employee_id?: string;
	status?: string;
	limit?: number;
	offset?: number;
}): Promise<TrainingAssignment[]> {
	const q = new URLSearchParams();
	if (params?.course_id) q.set("course_id", params.course_id);
	if (params?.employee_id) q.set("employee_id", params.employee_id);
	if (params?.status) q.set("status", params.status);
	if (params?.limit) q.set("limit", String(params.limit));
	if (params?.offset) q.set("offset", String(params.offset));
	const qs = q.toString();
	return request(`/training/v1/assignments${qs ? "?" + qs : ""}`);
}

export async function deleteTrainingAssignment(id: string): Promise<void> {
	await request(`/training/v1/assignments/${id}`, { method: "DELETE" });
}

export async function getLearningProgress(assignmentId: string, employeeId: string): Promise<LearningProgress[]> {
	return request(`/training/v1/assignments/${assignmentId}/employees/${employeeId}/progress`);
}

export async function updateLearningProgress(assignmentId: string, employeeId: string, chapterId: string, progress: { completed: boolean }): Promise<LearningProgress> {
	return request(`/training/v1/assignments/${assignmentId}/employees/${employeeId}/progress/${chapterId}`, {
		method: "PUT",
		body: JSON.stringify(progress),
	});
}

export async function completeLearning(assignmentId: string, employeeId: string): Promise<void> {
	await request(`/training/v1/assignments/${assignmentId}/employees/${employeeId}/complete`, {
		method: "POST",
	});
}

// ═══════════════════════════════════════════
// Exam API
// ═══════════════════════════════════════════

export interface Question {
	id: string;
	content: string;
	type?: string;
	options?: string[];
	answer?: string;
	score?: number;
	category?: string;
	difficulty?: string;
	tags?: string[];
	created_at?: string;
}

export interface ExamPaper {
	id: string;
	title: string;
	description?: string;
	duration?: number;
	total_score?: number;
	pass_score?: number;
	status?: string;
	question_count?: number;
	created_by?: string;
	created_at?: string;
}

export interface ExamRecord {
	id: string;
	paper_id: string;
	paper_title?: string;
	employee_id: string;
	started_at?: string;
	submitted_at?: string;
	score?: number;
	passed?: boolean;
	status?: string;
}

export async function listQuestions(params?: {
	type?: string;
	category?: string;
	difficulty?: string;
	search?: string;
	limit?: number;
	offset?: number;
}): Promise<Question[]> {
	const q = new URLSearchParams();
	if (params?.type) q.set("type", params.type);
	if (params?.category) q.set("category", params.category);
	if (params?.difficulty) q.set("difficulty", params.difficulty);
	if (params?.search) q.set("search", params.search);
	if (params?.limit) q.set("limit", String(params.limit));
	if (params?.offset) q.set("offset", String(params.offset));
	const qs = q.toString();
	return request(`/exam/v1/questions${qs ? "?" + qs : ""}`);
}

export async function createQuestion(data: Partial<Question>): Promise<Question> {
	return request("/exam/v1/questions", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function importQuestions(data: Partial<Question>[]): Promise<Question[]> {
	return request("/exam/v1/questions/import", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function updateQuestion(id: string, data: Partial<Question>): Promise<Question> {
	return request(`/exam/v1/questions/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export async function deleteQuestion(id: string): Promise<void> {
	await request(`/exam/v1/questions/${id}`, { method: "DELETE" });
}

export async function listExamPapers(params?: {
	status?: string;
	search?: string;
	limit?: number;
	offset?: number;
}): Promise<ExamPaper[]> {
	const q = new URLSearchParams();
	if (params?.status) q.set("status", params.status);
	if (params?.search) q.set("search", params.search);
	if (params?.limit) q.set("limit", String(params.limit));
	if (params?.offset) q.set("offset", String(params.offset));
	const qs = q.toString();
	return request(`/exam/v1/papers${qs ? "?" + qs : ""}`);
}

export async function createExamPaper(data: Partial<ExamPaper>): Promise<ExamPaper> {
	return request("/exam/v1/papers", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function updateExamPaper(id: string, data: Partial<ExamPaper>): Promise<ExamPaper> {
	return request(`/exam/v1/papers/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export async function deleteExamPaper(id: string): Promise<void> {
	await request(`/exam/v1/papers/${id}`, { method: "DELETE" });
}

export async function generateExamPaper(paperId: string): Promise<ExamPaper> {
	return request(`/exam/v1/papers/${paperId}/generate`, {
		method: "POST",
	});
}

export async function startExam(paperId: string): Promise<ExamRecord> {
	return request(`/exam/v1/papers/${paperId}/start`, {
		method: "POST",
	});
}

export async function submitExam(recordId: string, answers: Record<string, string | string[]>): Promise<ExamRecord> {
	return request(`/exam/v1/records/${recordId}/submit`, {
		method: "POST",
		body: JSON.stringify({ answers }),
	});
}

export async function getExamResult(recordId: string): Promise<ExamRecord> {
	return request(`/exam/v1/records/${recordId}`);
}

// ═══════════════════════════════════════════
// Onsite API
// ═══════════════════════════════════════════

export interface Device {
	id: string;
	code: string;
	name: string;
	type?: string;
	location?: string;
	status?: string;
	last_scanned_at?: string;
	created_at?: string;
}

export async function listDevices(params?: {
	type?: string;
	status?: string;
	search?: string;
	limit?: number;
	offset?: number;
}): Promise<Device[]> {
	const q = new URLSearchParams();
	if (params?.type) q.set("type", params.type);
	if (params?.status) q.set("status", params.status);
	if (params?.search) q.set("search", params.search);
	if (params?.limit) q.set("limit", String(params.limit));
	if (params?.offset) q.set("offset", String(params.offset));
	const qs = q.toString();
	return request(`/onsite/v1/devices${qs ? "?" + qs : ""}`);
}

export async function scanDevice(code: string): Promise<Device> {
	return request(`/onsite/v1/devices/scan`, {
		method: "POST",
		body: JSON.stringify({ code }),
	});
}

export async function createDevice(data: Partial<Device>): Promise<Device> {
	return request("/onsite/v1/devices", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export async function updateDevice(id: string, data: Partial<Device>): Promise<Device> {
	return request(`/onsite/v1/devices/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export async function deleteDevice(id: string): Promise<void> {
	await request(`/onsite/v1/devices/${id}`, { method: "DELETE" });
}

// ═══════════════════════════════════════════
// Analytics API
// ═══════════════════════════════════════════

export interface TrainingSummary {
	total_courses?: number;
	total_assignments?: number;
	completion_rate?: number;
	avg_score?: number;
	total_employees?: number;
	active_training?: number;
}

export interface SkillHeatmap {
	skill_name?: string;
	department_name?: string;
	level?: number;
	count?: number;
}

export interface QualityCorrelation {
	skill_name?: string;
	quality_score?: number;
	correlation?: number;
}

export async function getTrainingSummary(): Promise<TrainingSummary> {
	return request("/analytics/v1/training/summary");
}

export async function getSkillHeatmap(departmentId?: string): Promise<SkillHeatmap[]> {
	const qs = departmentId ? `?department_id=${departmentId}` : "";
	return request(`/analytics/v1/skills/heatmap${qs}`);
}

export async function getQualityCorrelation(departmentId?: string): Promise<QualityCorrelation[]> {
	const qs = departmentId ? `?department_id=${departmentId}` : "";
	return request(`/analytics/v1/quality/correlation${qs}`);
}
