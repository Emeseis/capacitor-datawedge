/* eslint-env node */

import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const androidDirectory = fileURLToPath(new URL('../android/', import.meta.url));
const gradleWrapper = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
const result = spawnSync(gradleWrapper, ['clean', 'build', 'test'], {
  cwd: androidDirectory,
  shell: process.platform === 'win32',
  stdio: 'inherit',
});

if (result.error) {
  console.error(`Failed to start the Android build: ${result.error.message}`);
}

process.exit(result.status ?? 1);
