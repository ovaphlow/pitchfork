import { useState } from "react";
import LoginForm from "./LoginForm";
import { setToken } from "@pitchfork/shared";

export default function AuthCard() {
	const [success, setSuccess] = useState(false);

	function handleLoginSuccess(t: string, _user: unknown) {
		setToken(t);
		setSuccess(true);
		setTimeout(() => {
			window.location.href = "/dashboard";
		}, 800);
	}

	if (success) {
		return (
			<div className="w-full max-w-md rounded-2xl border border-border bg-surface p-8 shadow-overlay">
				<div className="space-y-4 text-center">
					<div className="rounded-full bg-accent/10 w-12 h-12 flex items-center justify-center mx-auto">
						<svg className="w-6 h-6 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
						</svg>
					</div>
					<h3 className="text-lg font-semibold text-fg-emphasis">登录成功</h3>
					<p className="text-sm text-fg-muted">正在跳转...</p>
				</div>
			</div>
		);
	}

	return (
		<div className="w-full max-w-md rounded-2xl border border-border bg-surface p-8 shadow-overlay">
			<LoginForm onSuccess={handleLoginSuccess} />
		</div>
	);
}
