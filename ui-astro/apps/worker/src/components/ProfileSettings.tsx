import { Button, Card } from "@pitchfork/ui";

export default function ProfileSettings() {
  const handleLogout = () => {
    if (typeof window !== "undefined") {
      localStorage.removeItem("auth_token");
      window.location.href = "/login";
    }
  };

  return (
    <div className="space-y-4">
      {/* User Info */}
      <div className="flex flex-col items-center py-6">
        <div className="w-20 h-20 rounded-full bg-accent flex items-center justify-center text-2xl font-bold text-white mb-3">
          员
        </div>
        <h2 className="text-lg font-semibold text-fg-emphasis">员工姓名</h2>
        <p className="text-sm text-fg-dimmed">操作工 · 生产一部</p>
      </div>

      <Card title="设置">
        <div className="flex flex-col gap-3">
          <div className="flex items-center justify-between py-2">
            <span className="text-sm text-fg">消息通知</span>
            <label className="relative inline-flex items-center cursor-pointer">
              <input type="checkbox" className="sr-only peer" defaultChecked />
              <div className="w-9 h-5 bg-surface-alt rounded-full peer peer-checked:bg-accent after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:after:translate-x-4" />
            </label>
          </div>
          <div className="flex items-center justify-between py-2">
            <span className="text-sm text-fg">深色模式</span>
            <label className="relative inline-flex items-center cursor-pointer">
              <input type="checkbox" className="sr-only peer" defaultChecked />
              <div className="w-9 h-5 bg-surface-alt rounded-full peer peer-checked:bg-accent after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:after:translate-x-4" />
            </label>
          </div>
        </div>
      </Card>

      <div className="pt-4">
        <Button variant="danger" className="w-full" onClick={handleLogout}>
          退出登录
        </Button>
      </div>
    </div>
  );
}
