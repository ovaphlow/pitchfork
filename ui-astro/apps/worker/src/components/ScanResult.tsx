import { useState } from "react";
import { scanDevice, listKnowledgeEntries, type Device, type KnowledgeEntry } from "@pitchfork/shared";
import { Input, Button, Badge, LoadingSpinner } from "@pitchfork/ui";

export default function ScanResult() {
  const [code, setCode] = useState("");
  const [device, setDevice] = useState<Device | null>(null);
  const [entries, setEntries] = useState<KnowledgeEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const handleScan = async () => {
    if (!code.trim()) return;
    setLoading(true);
    setError("");
    setDevice(null);
    setEntries([]);
    try {
      const dev = await scanDevice(code.trim());
      setDevice(dev);
      const knowledge = await listKnowledgeEntries({ search: dev.name, limit: 20 });
      setEntries(knowledge);
    } catch (err) {
      setError(err instanceof Error ? err.message : "扫码失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      {/* Scan Input */}
      <div className="mb-4">
        <div className="flex gap-2 items-end">
          <div className="flex-1">
            <Input
              label="设备编码"
              placeholder="输入或扫描设备编码"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleScan()}
            />
          </div>
          <Button onClick={handleScan} loading={loading}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M3 7V5a2 2 0 0 1 2-2h2"/><path d="M17 3h2a2 2 0 0 1 2 2v2"/><path d="M21 17v2a2 2 0 0 1-2 2h-2"/><path d="M7 21H5a2 2 0 0 1-2-2v-2"/>
            </svg>
            扫码
          </Button>
        </div>
      </div>

      {error && (
        <div className="mb-4 p-3 rounded-lg bg-danger-bg border border-danger/30">
          <p className="text-sm text-danger">{error}</p>
        </div>
      )}

      {loading && (
        <div className="flex justify-center py-12">
          <LoadingSpinner />
        </div>
      )}

      {device && (
        <div className="mb-4 rounded-lg border border-border bg-surface p-4">
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-sm font-semibold text-fg-emphasis">{device.name}</h3>
            <Badge variant={device.status === "online" ? "success" : "warning"}>
              {device.status === "online" ? "在线" : "离线"}
            </Badge>
          </div>
          <div className="text-xs text-fg-muted space-y-1">
            {device.code && <p>编码: {device.code}</p>}
            {device.type && <p>类型: {device.type}</p>}
            {device.location && <p>位置: {device.location}</p>}
            {device.last_scanned_at && <p>上次扫码: {device.last_scanned_at}</p>}
          </div>
        </div>
      )}

      {/* Related Knowledge */}
      {entries.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-fg-emphasis mb-3">相关知识</h3>
          <div className="flex flex-col gap-2">
            {entries.map((entry) => (
              <a
                key={entry.id}
                href={`/knowledge/${entry.id}`}
                className="block no-underline rounded-lg border border-border bg-surface p-3 hover:bg-surface-alt transition-colors"
              >
                <h4 className="text-sm font-medium text-fg mb-1 line-clamp-1">
                  {entry.title}
                </h4>
                {entry.content && (
                  <p className="text-xs text-fg-muted line-clamp-2">
                    {entry.content.replace(/<[^>]+>/g, "")}
                  </p>
                )}
              </a>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
