import { useState } from "react";
import LoginForm from "./LoginForm";
import RegisterForm from "./RegisterForm";
import { setToken } from "@pitchfork/shared";

interface Props {
	mode: "login" | "register";
}

export default function AuthCard({ mode }: Props) {
	const [isLogin, setIsLogin] = useState(mode === "login");
	const [success, setSuccess] = useState(false);

	function handleLoginSuccess(t: string, _user: unknown) {
		setToken(t);
		setSuccess(true);
		// 跳转到主应用首页（可根据需要改路径）
		setTimeout(() => {
			window.location.href = "/";
		}, 800);
	}

	function handleRegisterSuccess(_user: unknown) {
		setIsLogin(true);
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

	return (
		<div className="w-full max-w-md rounded-2xl border border-gray-800 bg-gray-900/80 p-8 shadow-2xl backdrop-blur">
			<div className="mb-6">
				<div className="flex border-b border-gray-800">
					<button
						type="button"
						onClick={() => setIsLogin(true)}
						className={`flex-1 pb-3 text-sm font-medium transition-colors cursor-pointer
              ${
								isLogin
									? "border-b-2 border-indigo-500 text-white"
									: "text-gray-500 hover:text-gray-300"
							}`}
					>
						登录
					</button>
					<button
						type="button"
						onClick={() => setIsLogin(false)}
						className={`flex-1 pb-3 text-sm font-medium transition-colors cursor-pointer
              ${
								!isLogin
									? "border-b-2 border-indigo-500 text-white"
									: "text-gray-500 hover:text-gray-300"
							}`}
					>
						注册
					</button>
				</div>
			</div>

			{isLogin ? (
				<LoginForm
					onSuccess={handleLoginSuccess}
					onSwitchToRegister={() => setIsLogin(false)}
				/>
			) : (
				<RegisterForm
					onSuccess={handleRegisterSuccess}
					onSwitchToLogin={() => setIsLogin(true)}
				/>
			)}
		</div>
	);
}
