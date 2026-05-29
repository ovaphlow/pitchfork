import { type FormEvent, useState } from "react";
import { signUp } from "@pitchfork/shared";

interface Props {
	onSuccess: (user: unknown) => void;
	onSwitchToLogin: () => void;
}

export default function RegisterForm({ onSuccess, onSwitchToLogin }: Props) {
	const [email, setEmail] = useState("");
	const [password, setPassword] = useState("");
	const [confirmPassword, setConfirmPassword] = useState("");
	const [error, setError] = useState("");
	const [loading, setLoading] = useState(false);

	async function handleSubmit(e: FormEvent<HTMLFormElement>) {
		e.preventDefault();
		setError("");

		if (!email.trim() || !password.trim()) {
			setError("邮箱和密码不能为空");
			return;
		}

		if (password.length < 6) {
			setError("密码长度至少为 6 位");
			return;
		}

		if (password !== confirmPassword) {
			setError("两次输入的密码不一致");
			return;
		}

		setLoading(true);
		try {
			const result = await signUp(email, password);
			onSuccess(result.user);
		} catch (err) {
			setError(err instanceof Error ? err.message : "注册失败，请重试");
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
					htmlFor="reg-email"
					className="block text-sm font-medium text-gray-300 mb-1.5"
				>
					邮箱
				</label>
				<input
					id="reg-email"
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
					htmlFor="reg-password"
					className="block text-sm font-medium text-gray-300 mb-1.5"
				>
					密码
				</label>
				<input
					id="reg-password"
					type="password"
					autoComplete="new-password"
					required
					value={password}
					onChange={(e) => setPassword(e.target.value)}
					placeholder="至少 6 位"
					className="w-full rounded-lg border border-gray-700 bg-gray-800 px-4 py-2.5 text-sm text-gray-100
                     placeholder-gray-500 transition-colors
                     focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500/30 focus:outline-none"
				/>
			</div>

			<div>
				<label
					htmlFor="reg-confirm-password"
					className="block text-sm font-medium text-gray-300 mb-1.5"
				>
					确认密码
				</label>
				<input
					id="reg-confirm-password"
					type="password"
					autoComplete="new-password"
					required
					value={confirmPassword}
					onChange={(e) => setConfirmPassword(e.target.value)}
					placeholder="再次输入密码"
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
				{loading ? "注册中..." : "注册"}
			</button>

			<p className="text-center text-sm text-gray-500">
				已有账号？{" "}
				<button
					type="button"
					onClick={onSwitchToLogin}
					className="font-medium text-indigo-400 hover:text-indigo-300 transition-colors cursor-pointer"
				>
					立即登录
				</button>
			</p>
		</form>
	);
}
