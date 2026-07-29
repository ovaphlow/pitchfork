import { type FormEvent, useState } from "react";
import { Button, Input } from "@pitchfork/ui";
import { login } from "@pitchfork/shared/aceso";

interface Props {
	onSuccess: () => void;
}

export default function LoginForm({ onSuccess }: Props) {
	const [identifier, setIdentifier] = useState("");
	const [password, setPassword] = useState("");
	const [error, setError] = useState("");
	const [loading, setLoading] = useState(false);

	async function handleSubmit(e: FormEvent<HTMLFormElement>) {
		e.preventDefault();
		setError("");

		if (!identifier.trim() || !password.trim()) {
			setError("账号和密码不能为空");
			return;
		}

		setLoading(true);
		try {
			await login(identifier, password);
			onSuccess();
		} catch (err) {
			setError(err instanceof Error ? err.message : "登录失败，请重试");
		} finally {
			setLoading(false);
		}
	}

	return (
		<form onSubmit={handleSubmit} className="space-y-5">
			{error && (
				<div className="rounded-lg bg-danger-bg px-4 py-3 text-sm text-danger border border-danger/20">
					{error}
				</div>
			)}

			<Input
				id="login-identifier"
				label="账号"
				autoComplete="username"
				required
				value={identifier}
				onChange={(event) => setIdentifier(event.target.value)}
				placeholder="请输入账号"
			/>

			<Input
				id="login-password"
				label="密码"
				type="password"
				autoComplete="current-password"
				required
				value={password}
				onChange={(event) => setPassword(event.target.value)}
				placeholder="请输入密码"
			/>

			<Button type="submit" className="w-full" loading={loading}>
				登录
			</Button>

			<div className="text-right">
				<a href="/forgot-password" className="text-sm text-accent transition-colors hover:text-fg-emphasis">
					忘记密码？
				</a>
			</div>
		</form>
	);
}
