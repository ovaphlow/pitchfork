# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: elderly-care-lifecycle.spec.ts >> 养老院入住照护周期生命周期验收 >> 无执行中任务时离院成功且入住从活动列表消失
- Location: apps/aceso/e2e/elderly-care-lifecycle.spec.ts:208:3

# Error details

```
Error: browserType.launch: Executable doesn't exist at /home/ovaphlow/.cache/ms-playwright/chromium_headless_shell-1234/chrome-headless-shell-linux64/chrome-headless-shell
╔════════════════════════════════════════════════════════════╗
║ Looks like Playwright was just installed or updated.       ║
║ Please run the following command to download new browsers: ║
║                                                            ║
║     pnpm exec playwright install                           ║
║                                                            ║
║ <3 Playwright Team                                         ║
╚════════════════════════════════════════════════════════════╝
```