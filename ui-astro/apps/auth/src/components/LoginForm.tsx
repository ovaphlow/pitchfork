import { type FormEvent, useState } from "react";
import { login } from "@pitchfork/shared";

interface Props {
	onSuccess: (token: string, user: unknown) => void;
}

export default function LoginForm({ onSuccess }: Props) {
	const [email, setEmail] = useState("");
	const [password, setPassword] = useState("");
	const [error, setError] = useState("");
	const [loading, setLoading] = useState(false);

	async function handleSubmit(e: FormEvent<HTMLFormElement>) {
		e.preventDefault();
		setError("");

		if (!email.trim() || !password.trim()) {
			setError("邮箱和密码不能为空");
			return;
		}

		setLoading(true);
		try {
			const result = await login(email, password);
			onSuccess(result.token, result.user);
		} catch (err) {
			setError(err instanceof Error ? err.message : "登录失败，请重试");
		} finally {
			setLoading(false);
		}
	}

	return (
		<form onSubmit={handleSubmit} className="space-y-5">
			{error && (
				<div className="rounded-lg bg-red-500/10 px-4 py-3 text-sm text-red-400 border border-red-500/20">
					{error}
				</div>
			)}

			<div>
				<label
					htmlFor="login-email"
					className="block text-sm font-medium text-gray-300 mb-1.5"
				>
					邮箱
				</label>
				<input
					id="login-email"
					type="email"
					autoComplete="email"
					required
					value={email}
					onChange={(e) => setEmail(e.target.value)}
					placeholder="your@email.com"
					className="w-full rounded-lg border border-gray-700 bg-gray-800 px-4 py-2.5 text-sm text-gray-100
                     placeholder-gray-500 transition-colors
                     focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500/30 focus:outline-none"
				/>
			</div>

			<div>
				<label
					htmlFor="login-password"
					className="block text-sm font-medium text-gray-300 mb-1.5"
				>
					密码
				</label>
				<input
					id="login-password"
					type="password"
					autoComplete="current-password"
					required
					value={password}
					onChange={(e) => setPassword(e.target.value)}
					placeholder="••••••••"
					className="w-full rounded-lg border border-gray-700 bg-gray-800 px-4 py-2.5 text-sm text-gray-100
                     placeholder-gray-500 transition-colors
                     focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500/30 focus:outline-none"
				/>
			</div>

			<button
				type="submit"
				disabled={loading}
				className="w-full rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white
                   transition-all hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500/50
                   disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
			>
				{loading ? "登录中..." : "登录"}
			</button>
		</form>
	);
}
