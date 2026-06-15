import { useState, useEffect } from "react";
import { listEmployeeSkills, listEmployeeCertificates, type EmployeeSkill, type EmployeeCertificate } from "@pitchfork/shared";
import { Badge, LoadingSpinner, EmptyState } from "@pitchfork/ui";

export default function SkillProfile() {
  const [skills, setSkills] = useState<EmployeeSkill[]>([]);
  const [certificates, setCertificates] = useState<EmployeeCertificate[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const employeeId = "me"; // In real app, get from auth
    Promise.all([
      listEmployeeSkills(employeeId).catch(() => [] as EmployeeSkill[]),
      listEmployeeCertificates(employeeId).catch(() => [] as EmployeeCertificate[]),
    ])
      .then(([skills, certs]) => {
        setSkills(skills);
        setCertificates(certs);
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner />
      </div>
    );
  }

  const levelLabel = (level?: number) => {
    if (!level) return "未评估";
    if (level <= 2) return "初级";
    if (level <= 3) return "中级";
    if (level <= 4) return "高级";
    return "专家";
  };

  return (
    <div className="space-y-6">
      {/* Skill Radar Placeholder */}
      <div className="rounded-lg border border-border bg-surface p-5">
        <h3 className="text-sm font-semibold text-fg-emphasis mb-4">技能概览</h3>
        <div className="flex flex-col gap-3">
          {skills.length === 0 ? (
            <p className="text-sm text-fg-dimmed text-center py-4">暂无技能数据</p>
          ) : (
            skills.map((s) => (
              <div key={s.id}>
                <div className="flex items-center justify-between mb-1">
                  <span className="text-sm text-fg">{s.skill_name || "未命名技能"}</span>
                  <Badge variant={s.level && s.level >= 4 ? "success" : s.level && s.level >= 3 ? "info" : "default"}>
                    {levelLabel(s.level)}
                  </Badge>
                </div>
                <div className="h-2 rounded-full bg-surface-alt overflow-hidden">
                  <div
                    className="h-full rounded-full bg-accent transition-all"
                    style={{ width: `${((s.level ?? 0) / 5) * 100}%` }}
                  />
                </div>
              </div>
            ))
          )}
        </div>
      </div>

      {/* Certificates */}
      <div className="rounded-lg border border-border bg-surface p-5">
        <h3 className="text-sm font-semibold text-fg-emphasis mb-4">我的证书</h3>
        {certificates.length === 0 ? (
          <EmptyState icon="🏅" title="暂无证书" description="完成培训后可获得证书" />
        ) : (
          <div className="flex flex-col gap-2">
            {certificates.map((c) => (
              <div key={c.id} className="flex items-center justify-between p-3 rounded-md bg-surface-alt">
                <div>
                  <p className="text-sm font-medium text-fg">{c.certificate_name || "证书"}</p>
                  {c.issued_at && (
                    <p className="text-xs text-fg-dimmed">颁发: {c.issued_at}</p>
                  )}
                </div>
                {c.expires_at && (
                  <Badge variant="warning">{c.expires_at} 到期</Badge>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
