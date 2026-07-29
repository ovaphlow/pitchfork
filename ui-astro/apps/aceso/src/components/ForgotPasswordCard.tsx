export default function ForgotPasswordCard() {
	return (
		<div className="space-y-5 text-center">
			<div className="space-y-2">
				<h2 className="text-xl font-semibold text-fg-emphasis">忘记密码</h2>
				<p className="text-sm leading-6 text-fg-muted">
					请联系系统管理员完成身份核验。管理员核验后会将密码重置为临时密码；请使用该临时密码登录并立即设置新密码。
				</p>
			</div>
			<a href="/login" className="inline-block text-sm text-accent transition-colors hover:text-fg-emphasis">返回登录</a>
		</div>
	);
}
