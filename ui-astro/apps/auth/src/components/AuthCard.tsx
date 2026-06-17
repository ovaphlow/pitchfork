import { useState } from "react";
import LoginForm from "./LoginForm";
import { setToken } from "@pitchfork/shared";

interface Props {
	mode: "login" | "register";
}

export default function AuthCard({ mode }: Props) {
	const [success, setSuccess] = useState(false);

	function handleLoginSuccess(t: string, _user: unknown) {
		setToken(t);
		setSuccess(true);
		// 跳转到主应用首页
		setTimeout(() => {
			window.location.href = "/dashboard";
		}, 800);
	}

	if (success) {
		return (
			<div className="w-full max-w-md rounded-2xl border border-gray-800 bg-gray-900/80 p-8 shadow-2xl backdrop-blur">
				<div className="space-y-4 text-center">
					<div className="rounded-full bg-indigo-500/10 w-12 h-12 flex items-center justify-center mx-auto">
						<svg
							className="w-6 h-6 text-indigo-400"
							fill="none"
							stroke="currentColor"
							viewBox="0 0 24 24"
						>
							<path
								strokeLinecap="round"
								strokeLinejoin="round"
								strokeWidth={2}
								d="M5 13l4 4L19 7"
							/>
						</svg>
					</div>
					<h3 className="text-lg font-semibold text-white">登录成功</h3>
					<p className="text-sm text-gray-400">正在跳转...</p>
				</div>
			</div>
		);
	}

	if (mode === "register") {
		return (
			<div className="w-full max-w-md rounded-2xl border border-gray-800 bg-gray-900/80 p-8 shadow-2xl backdrop-blur">
				<div className="space-y-4 text-center">
					<h3 className="text-lg font-semibold text-white">注册功能已关闭</h3>
					<p className="text-sm text-gray-400">
						请联系管理员开通账号
					</p>
					<a
						href="/login"
						className="inline-block mt-4 text-sm text-indigo-400 hover:text-indigo-300"
					>
						返回登录
					</a>
				</div>
			</div>
		);
	}
	return (
		<div className="w-full max-w-md rounded-2xl border border-gray-800 bg-gray-900/80 p-8 shadow-2xl backdrop-blur">
			<LoginForm onSuccess={handleLoginSuccess} />
		</div>
	);
}
