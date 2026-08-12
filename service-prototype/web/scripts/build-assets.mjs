import { copyFile, mkdir } from "node:fs/promises";

await mkdir("web/static", { recursive: true });
await copyFile("node_modules/htmx.org/dist/htmx.min.js", "web/static/htmx.min.js");
console.log("web/static/htmx.min.js copied");
