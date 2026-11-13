import { spawn } from "node:child_process";
import { copyFile, mkdir } from "node:fs/promises";

const tailwindExecutable = process.platform === "win32"
  ? "node_modules\\.bin\\tailwindcss.cmd"
  : "node_modules/.bin/tailwindcss";
const tailwindArguments = ["-i", "./web/assets/app.css", "-o", "./web/static/app.css", "--minify"];

await mkdir("web/static", { recursive: true });
await new Promise((resolve, reject) => {
  const tailwindProcess = spawn(tailwindExecutable, tailwindArguments, {
    shell: process.platform === "win32",
    stdio: "inherit",
  });
  tailwindProcess.on("error", reject);
  tailwindProcess.on("exit", (exitCode) => {
    if (exitCode === 0) {
      resolve();
      return;
    }
    reject(new Error(`Tailwind build exited with status ${exitCode}`));
  });
});
await copyFile("node_modules/htmx.org/dist/htmx.min.js", "web/static/htmx.min.js");
