import { useState } from "react";
import { logout } from "@pitchfork/shared/aceso";
import { Button } from "@pitchfork/ui";

export default function SignOutButton() {
  const [pending, setPending] = useState(false);

  async function handleSignOut() {
    setPending(true);
    try {
      await logout();
    } catch {
      // The session can already be absent; navigating to login is still the desired result.
    } finally {
      window.location.assign("/login");
    }
  }

  return (
    <Button variant="ghost" size="sm" loading={pending} onClick={handleSignOut}>
      退出登录
    </Button>
  );
}
