import { useEffect, useState } from "react";
import { getCurrentSession } from "@pitchfork/shared/aceso";
import { LoadingSpinner } from "@pitchfork/ui";

export default function SessionGuard() {
  const [checking, setChecking] = useState(true);

  useEffect(() => {
    getCurrentSession()
      .catch(() => undefined)
      .finally(() => setChecking(false));
  }, []);

  if (!checking) return null;
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-bg">
      <LoadingSpinner size={28} />
    </div>
  );
}
