import { useEffect, useRef, useState } from "react";
import { getCurrentSession } from "@pitchfork/shared/aceso";
import { LoadingSpinner } from "@pitchfork/ui";

// IdP 空闲窗口已按 031 决策放宽到 2h（service-idp-go/.env）。
// 心跳间隔取 10min，远小于 2h，保证前台标签页持续续期；后台标签页不续期。
const HEARTBEAT_INTERVAL_MS = 10 * 60 * 1000; // 10min 前台心跳

// 标签页转回可见时，若距上次 ping 超过 5min 则立即补一次，
// 避免恰在空闲窗口边缘切回时掉线（5min 是 2h 空闲窗口的安全余量）。
const VISIBLE_CATCHUP_THRESHOLD_MS = 5 * 60 * 1000; // 5min

export default function SessionGuard() {
  const [checking, setChecking] = useState(true);
  const lastPingAtRef = useRef<number>(Date.now());

  useEffect(() => {
    getCurrentSession()
      .catch(() => undefined)
      .finally(() => setChecking(false));
  }, []);

  useEffect(() => {
    const ping = () => {
      if (document.visibilityState !== "visible") return;
      lastPingAtRef.current = Date.now();
      // 静默续期：redirectOnUnauthorized=false 避免后台请求把用户踢去登录，失败也不打扰用户。
      getCurrentSession(false).catch(() => undefined);
    };

    const onVisibilityChange = () => {
      if (document.visibilityState !== "visible") return;
      if (Date.now() - lastPingAtRef.current > VISIBLE_CATCHUP_THRESHOLD_MS) {
        ping();
      }
    };

    const interval = setInterval(ping, HEARTBEAT_INTERVAL_MS);
    document.addEventListener("visibilitychange", onVisibilityChange);

    return () => {
      clearInterval(interval);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, []);

  if (!checking) return null;
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-bg">
      <LoadingSpinner size={28} />
    </div>
  );
}
