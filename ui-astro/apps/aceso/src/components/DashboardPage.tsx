export default function DashboardPage() {
	return (
		<div className="space-y-6">
			<div>
				<h1 className="text-2xl font-bold text-fg-emphasis">欢迎回来</h1>
				<p className="text-sm text-fg-muted mt-1">这是您的 Aceso 管理控制台</p>
			</div>

			<div className="grid grid-cols-1 md:grid-cols-3 gap-4">
				<div className="rounded-lg border border-border bg-surface p-5">
					<div className="flex items-center gap-3">
						<div className="w-10 h-10 rounded-lg bg-accent/10 flex items-center justify-center">
							<svg className="w-5 h-5 text-accent" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
								<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/>
							</svg>
						</div>
						<div>
							<p className="text-xs text-fg-dimmed uppercase tracking-wider">总用户数</p>
							<p className="text-xl font-semibold text-fg-emphasis">—</p>
						</div>
					</div>
				</div>

				<div className="rounded-lg border border-border bg-surface p-5">
					<div className="flex items-center gap-3">
						<div className="w-10 h-10 rounded-lg bg-success-bg flex items-center justify-center">
							<svg className="w-5 h-5 text-success" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
								<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
							</svg>
						</div>
						<div>
							<p className="text-xs text-fg-dimmed uppercase tracking-wider">今日活跃</p>
							<p className="text-xl font-semibold text-fg-emphasis">—</p>
						</div>
					</div>
				</div>

				<div className="rounded-lg border border-border bg-surface p-5">
					<div className="flex items-center gap-3">
						<div className="w-10 h-10 rounded-lg bg-info-bg flex items-center justify-center">
							<svg className="w-5 h-5 text-info" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
								<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
							</svg>
						</div>
						<div>
							<p className="text-xs text-fg-dimmed uppercase tracking-wider">系统状态</p>
							<p className="text-xl font-semibold text-success">运行中</p>
						</div>
					</div>
				</div>
			</div>
		</div>
	);
}
