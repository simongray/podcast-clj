// Print the results that a device test wrote to the file given as the
// argument, and exit with 1 when a check failed or there are no results.
import fs from 'node:fs';

const file = process.argv[2];
if (!fs.existsSync(file)) {
  console.error(`No results in ${file}: the app didn't report in time`);
  process.exit(1);
}

const { platform, environment, results } = JSON.parse(fs.readFileSync(file, 'utf8'));
for (const [name, ok, detail] of results) {
  console.log(`${ok ? '✓' : '✗'} ${name}\n    ${detail}`);
}
if (environment) {
  console.log(`\nThe engine has: ${JSON.stringify(environment)}`);
}
const passed = results.filter(([, ok]) => ok).length;
console.log(`\n${passed} of ${results.length} checks passed in ${platform}`);
process.exit(passed === results.length ? 0 : 1);
