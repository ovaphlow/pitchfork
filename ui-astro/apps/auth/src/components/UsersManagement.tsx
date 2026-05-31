import { useEffect, useState } from "react";
import { listUsers, listDepartments, updateUserStatus } from "@pitchfork/shared";

interface User {
	id: string;
	email: string;
	username: string;
	phone: string;
	user_type: string;
	status: string;
	dept_id: string;
	created_at: string;
	updated_at: string;
}

export default function UsersManagement() {
	const [users, setUsers] = useState<User[]>([]);
	const [deptMap, setDeptMap] = useState<Record<string, string>>({});
	const [loading, setLoading] = useState(true);
	const [search, setSearch] = useState("");

	async function load() {
		setLoading(true);
		try {
			const [data, depts] = await Promise.all([
				listUsers({ search: search || undefined }),
				listDepartments(),
			]);
			setUsers(data as User[]);
			const map: Record<string, string> = {};
			(depts as { id: string; payload: { name?: string } }[]).forEach((d) => {
				map[d.id] = d.payload?.name || "";
			});
			setDeptMap(map);
		} catch {
			// ignore
		} finally {
			setLoading(false);
		}
	}

	useEffect(() => {
		load();
	}, [search]);

	async function toggleStatus(user: User) {
		const newStatus = user.status === "active" ? "disabled" : "active";
		try {
			await updateUserStatus(user.id, newStatus);
			await load();
		} catch {
			// ignore
		}
	}

	return (
		<div className="space-y-4">
			<div className="flex items-center justify-between">
				<h2 className="text-lg font-semibold text-fg">用户列表</h2>
				<input
					type="text"
					placeholder="搜索用户名/邮箱/手机..."
					value={search}
					onChange={(e) => setSearch(e.target.value)}
					className="h-9 w-64 rounded-md border border-border bg-surface px-3 text-sm text-fg placeholder:text-fg-dimmed focus:outline-none focus:ring-2 focus:ring-accent"
				/>
			</div>

			<div className="overflow-hidden rounded-lg border border-border">
				<table className="w-full text-sm">
					<thead className="bg-surface-alt">
						<tr>
							<th className="px-4 py-3 text-left text-fg-muted font-medium">用户名</th>
							<th className="px-4 py-3 text-left text-fg-muted font-medium">邮箱</th>
							<th className="px-4 py-3 text-left text-fg-muted font-medium">手机</th>
							<th className="px-4 py-3 text-left text-fg-muted font-medium">部门</th>
							<th className="px-4 py-3 text-left text-fg-muted font-medium">类型</th>
							<th className="px-4 py-3 text-left text-fg-muted font-medium">状态</th>
							<th className="px-4 py-3 text-left text-fg-muted font-medium">操作</th>
						</tr>
					</thead>
					<tbody className="divide-y divide-border">
						{loading ? (
							<tr>
								<td colSpan={7} className="px-4 py-8 text-center text-fg-dimmed">加载中...</td>
							</tr>
						) : users.length === 0 ? (
							<tr>
								<td colSpan={7} className="px-4 py-8 text-center text-fg-dimmed">暂无用户</td>
							</tr>
						) : (
							users.map((u) => (
								<tr key={u.id} className="hover:bg-surface/50">
									<td className="px-4 py-3 text-fg">{u.username || "-"}</td>
									<td className="px-4 py-3 text-fg-muted">{u.email}</td>
									<td className="px-4 py-3 text-fg-muted">{u.phone || "-"}</td>
									<td className="px-4 py-3 text-fg-muted">{deptMap[u.dept_id] || "-"}</td>
									<td className="px-4 py-3">
										<span className="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium bg-accent-subtle text-accent">
											{u.user_type}
										</span>
									</td>
									<td className="px-4 py-3">
										<span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${
											u.status === "active" ? "bg-success/10 text-success" : "bg-warning/10 text-warning"
										}`}>
											{u.status === "active" ? "正常" : "禁用"}
										</span>
									</td>
									<td className="px-4 py-3">
										<button
											onClick={() => toggleStatus(u)}
											className="cursor-pointer rounded px-2 py-1 text-xs font-medium border border-border text-fg-muted hover:bg-surface-alt hover:text-fg transition-colors"
										>
											{u.status === "active" ? "禁用" : "启用"}
										</button>
									</td>
								</tr>
							))
						)}
					</tbody>
				</table>
			</div>
		</div>
	);
}
