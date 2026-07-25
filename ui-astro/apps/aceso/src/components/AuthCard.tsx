import { useEffect, useState } from "react";
import { LoadingSpinner } from "@pitchfork/ui";
import { getCurrentSession } from "@pitchfork/shared/aceso";
import LoginForm from "./LoginForm";

export default function AuthCard() {
	const [checking, setChecking] = useState(true);
	const [success, setSuccess] = useState(false);

	useEffect(() => {
		getCurrentSession(false)
			.then(() => window.location.assign("/dashboard"))
			.catch(() => setChecking(false));
	}, []);

	function handleLoginSuccess() {
		setSuccess(true);
		setTimeout(() => {
			window.location.assign("/dashboard");
		}, 250);
	}

	if (checking) {
		return (
			<div className="flex w-full max-w-md justify-center rounded-lg border border-border bg-surface p-10 shadow-overlay">
				<LoadingSpinner />
			</div>
		);
	}

	if (success) {
		return (
			<div className="w-full max-w-md rounded-lg border border-border bg-surface p-8 shadow-overlay">
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
		<div className="w-full max-w-md rounded-lg border border-border bg-surface p-8 shadow-overlay">
			<LoginForm onSuccess={handleLoginSuccess} />
		</div>
	);
}
