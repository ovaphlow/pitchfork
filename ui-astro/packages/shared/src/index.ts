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

// ---- Departments ----

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
