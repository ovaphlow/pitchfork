import { defineConfig } from "astro/config";
import react from "@astrojs/react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  integrations: [react()],
  vite: {
    plugins: [tailwindcss()],
    ssr: {
      noExternal: ["@pitchfork/ui", "@pitchfork/shared", "jsencrypt"],
    },
  },
  server: {
    host: "0.0.0.0",
    port: 4323,
  },
});
