import { useEffect, useState } from "react";
import {
	listDepartments,
	createDepartment,
	updateDepartment,
	deleteDepartment,
} from "@pitchfork/shared";

interface Department {
	id: string;
	category: string;
	code: string;
	parent_code: string;
	root_code: string;
	payload: { name?: string; description?: string };
	create_time: string;
	update_time: string;
}

export default function DepartmentsManagement() {
	const [departments, setDepartments] = useState<Department[]>([]);
	const [loading, setLoading] = useState(true);
	const [editing, setEditing] = useState<string | null>(null);
	const [showForm, setShowForm] = useState(false);
	const [form, setForm] = useState({ name: "", code: "", parent_code: "", description: "" });
	const [error, setError] = useState("");

	const deptMap = new Map<string, string>();
	departments.forEach((d) => deptMap.set(d.code, d.payload?.name || d.code));

	function parentName(code: string) {
		return code ? deptMap.get(code) || code : "";
	}

	function depth(code: string, visited = new Set<string>()): number {
		if (!code) return 0;
		if (visited.has(code)) return 0;
		visited.add(code);
		const p = departments.find((d) => d.code === code);
		return 1 + depth(p?.parent_code || "", visited);
	}

	const sorted = [...departments].sort(
		(a, b) => depth(a.code) - depth(b.code) || a.create_time.localeCompare(b.create_time),
	);

	async function load() {
		setLoading(true);
		try {
			const data = await listDepartments();
			setDepartments(data as Department[]);
		} catch {
			// ignore
		} finally {
			setLoading(false);
		}
	}

	useEffect(() => {
		load();
	}, []);

	function resetForm() {
		setForm({ name: "", code: "", parent_code: "", description: "" });
		setEditing(null);
		setShowForm(false);
		setError("");
	}

	async function handleSave() {
		setError("");
		try {
			if (editing) {
				await updateDepartment(editing, form);
			} else {
				await createDepartment(form);
			}
			resetForm();
			await load();
		} catch (e) {
			setError(e instanceof Error ? e.message : "操作失败");
		}
	}

	function startEdit(dept: Department) {
		setEditing(dept.id);
		setForm({
			name: dept.payload?.name || "",
			code: dept.code,
			parent_code: dept.parent_code || "",
			description: dept.payload?.description || "",
		});
		setShowForm(true);
	}

	async function handleDelete(id: string) {
		if (!confirm("确定删除该部门？")) return;
		try {
			await deleteDepartment(id);
			await load();
		} catch {
			// ignore
		}
	}

	return (
		<div class="space-y-4">
			<div class="flex items-center justify-between">
				<h2 class="text-lg font-semibold text-fg">部门管理</h2>
				<button
					onClick={() => { resetForm(); setShowForm(true); }}
					class="cursor-pointer h-9 rounded-md bg-accent px-4 text-sm font-medium text-white hover:opacity-90 transition-opacity"
				>
					新增部门
				</button>
			</div>

			{showForm && (
				<div class="rounded-lg border border-border bg-surface p-4 space-y-3">
					{error && (
						<div class="rounded-md bg-danger/10 border border-danger/20 px-3 py-2 text-sm text-danger">
							{error}
						</div>
					)}
					<div class="grid grid-cols-2 gap-3">
						<div>
							<label class="block text-xs text-fg-muted mb-1">名称</label>
							<input
								type="text"
								value={form.name}
								onChange={(e) => setForm({ ...form, name: e.target.value })}
								class="h-9 w-full rounded-md border border-border bg-surface-alt px-3 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus:ring-2 focus:ring-accent"
							/>
						</div>
						<div>
							<label class="block text-xs text-fg-muted mb-1">编码</label>
							<input
								type="text"
								value={form.code}
								onChange={(e) => setForm({ ...form, code: e.target.value })}
								disabled={!!editing}
								class="h-9 w-full rounded-md border border-border bg-surface-alt px-3 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus:ring-2 focus:ring-accent disabled:opacity-50"
							/>
						</div>
						<div>
							<label class="block text-xs text-fg-muted mb-1">上级部门</label>
							<select
								value={form.parent_code}
								onChange={(e) => setForm({ ...form, parent_code: e.target.value })}
								class="h-9 w-full rounded-md border border-border bg-surface-alt px-3 text-sm text-fg focus:outline-none focus:ring-2 focus:ring-accent"
							>
								<option value="">（顶级部门）</option>
								{departments
									.filter((d) => d.id !== editing)
									.map((d) => (
										<option key={d.id} value={d.code}>
											{d.payload?.name || d.code}
										</option>
									))}
							</select>
						</div>
						<div>
							<label class="block text-xs text-fg-muted mb-1">描述</label>
							<input
								type="text"
								value={form.description}
								onChange={(e) => setForm({ ...form, description: e.target.value })}
								class="h-9 w-full rounded-md border border-border bg-surface-alt px-3 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus:ring-2 focus:ring-accent"
							/>
						</div>
					</div>
					<div class="flex gap-2 justify-end">
						<button
							onClick={resetForm}
							class="cursor-pointer h-8 rounded-md border border-border px-3 text-xs text-fg-muted hover:bg-surface-alt transition-colors"
						>
							取消
						</button>
						<button
							onClick={handleSave}
							class="cursor-pointer h-8 rounded-md bg-accent px-3 text-xs text-white hover:opacity-90 transition-opacity"
						>
							{editing ? "保存" : "创建"}
						</button>
					</div>
				</div>
			)}

			<div class="overflow-hidden rounded-lg border border-border">
				<table class="w-full text-sm">
					<thead class="bg-surface-alt">
						<tr>
							<th class="px-4 py-3 text-left text-fg-muted font-medium">名称</th>
							<th class="px-4 py-3 text-left text-fg-muted font-medium">编码</th>
							<th class="px-4 py-3 text-left text-fg-muted font-medium">上级部门</th>
							<th class="px-4 py-3 text-left text-fg-muted font-medium">描述</th>
							<th class="px-4 py-3 text-left text-fg-muted font-medium">操作</th>
						</tr>
					</thead>
					<tbody class="divide-y divide-border">
						{loading ? (
							<tr>
								<td colspan="5" class="px-4 py-8 text-center text-fg-dimmed">加载中...</td>
							</tr>
						) : sorted.length === 0 ? (
							<tr>
								<td colspan="5" class="px-4 py-8 text-center text-fg-dimmed">暂无部门</td>
							</tr>
						) : (
							sorted.map((d) => {
								const dpt = depth(d.code);
								return (
									<tr key={d.id} class="hover:bg-surface/50">
										<td
											class="px-4 py-3 text-fg"
											style={{ paddingLeft: `${16 + (dpt - 1) * 24}px` }}
										>
											{dpt > 1 && (
												<span class="text-fg-dimmed mr-2 select-none">
													{dpt === 2 ? "└ " : "  "}
												</span>
											)}
											{d.payload?.name || "-"}
										</td>
										<td class="px-4 py-3 text-fg-muted font-mono text-xs">{d.code}</td>
										<td class="px-4 py-3 text-fg-muted">{parentName(d.parent_code) || "-"}</td>
										<td class="px-4 py-3 text-fg-muted">{d.payload?.description || "-"}</td>
										<td class="px-4 py-3 space-x-1">
											<button
												onClick={() => startEdit(d)}
												class="cursor-pointer rounded px-2 py-1 text-xs font-medium border border-border text-fg-muted hover:bg-surface-alt hover:text-fg transition-colors"
											>
												编辑
											</button>
											<button
												onClick={() => handleDelete(d.id)}
												class="cursor-pointer rounded px-2 py-1 text-xs font-medium border border-border text-danger hover:bg-danger/10 transition-colors"
											>
												删除
											</button>
										</td>
									</tr>
								);
							})
						)}
					</tbody>
				</table>
			</div>
		</div>
	);
}
