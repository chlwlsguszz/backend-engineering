import { defineConfig } from "@playwright/test";

const port = 15173;
const baseURL = `http://127.0.0.1:${port}`;

export default defineConfig({
  testDir: "./e2e",
  use: { baseURL, browserName: "chromium", trace: "retain-on-failure" },
  webServer: {
    command: `node ./node_modules/next/dist/bin/next start -H 127.0.0.1 -p ${port}`,
    url: baseURL,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
