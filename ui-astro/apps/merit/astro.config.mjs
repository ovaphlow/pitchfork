import { defineConfig } from "astro/config";
import svelte from "@astrojs/svelte";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
	output: "static",
	integrations: [svelte()],
	vite: {
		plugins: [tailwindcss()],
	},
	server: {
		host: "0.0.0.0",
		port: 4325,
	},
});
