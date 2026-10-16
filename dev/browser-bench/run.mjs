// Time the parse of corpus feeds in a headless Chrome, stage by stage, by
// the build of browser_bench.cljs:
//
//     clojure -M:cljs:browser-bench release browser-bench
//     node dev/browser-bench/run.mjs anchor pc20-nashownotes simplecast
//
// It prints the median milliseconds of each stage for each feed. RUNS and
// WARMUP set the runs of each stage, 7 after 3 by default, and OUT names a
// file for the timings as JSON. COLD=n times the first n parses of each
// feed in a fresh page instead, the first of which is a cold one, and
// THROTTLE=4 slows the CPU down four times, as that of a phone is.
//
// PROFILE=feed:stage records the profile of that stage instead, in
// target/browser-bench, and prints the functions that take the most time,
// which a build with pseudo-names names:
//
//     clojure -M:cljs:browser-bench release browser-bench \
//       --config-merge '{:compiler-options {:pseudo-names true}}'
//     PROFILE='simplecast:rss/parse' node dev/browser-bench/run.mjs
//
// It serves the build and dev-resources/corpus on 127.0.0.1. CHROME names
// another browser of Chromium.

import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';

const feeds = process.argv.length > 2
  ? process.argv.slice(2)
  : ['anchor', 'pc20-nashownotes', 'simplecast'];
const runs = Number(process.env.RUNS ?? 7);
const warmup = Number(process.env.WARMUP ?? 3);
const profile = process.env.PROFILE;
const cold = Number(process.env.COLD ?? 0);
const throttle = Number(process.env.THROTTLE ?? 1);
const dir = path.resolve('target/browser-bench');
const corpus = path.resolve('dev-resources/corpus');
const chrome = process.env.CHROME ?? (process.platform === 'darwin'
  ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
  : 'google-chrome');
const page = '<!doctype html><meta charset="utf-8"><title>Bench</title>' +
             '<script src="/js/bench.js"></script>';

const server = http.createServer(async (req, res) => {
  const wanted = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  if (wanted === '/') {
    return res.writeHead(200, { 'Content-Type': 'text/html' }).end(page);
  }
  const [root, rest] = wanted.startsWith('/corpus/')
    ? [corpus, wanted.slice('/corpus'.length)]
    : [dir, wanted];
  const file = path.join(root, rest);
  if (!file.startsWith(root)) return res.writeHead(403).end();
  try {
    const body = await fs.readFile(file);
    const type = file.endsWith('.js') ? 'text/javascript' : 'text/xml';
    res.writeHead(200, { 'Content-Type': type }).end(body);
  } catch {
    res.writeHead(404).end();
  }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));

const userData = await fs.mkdtemp(path.join(os.tmpdir(), 'browser-bench-'));
const browser = spawn(chrome, [
  '--headless=new', '--remote-debugging-port=0',
  `--user-data-dir=${userData}`,
  '--no-first-run', '--no-default-browser-check',
  ...(process.env.CI ? ['--no-sandbox'] : []),
  'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });

const finish = async code => {
  browser.kill();
  server.close();
  await fs.rm(userData, { recursive: true, force: true }).catch(() => {});
  process.exit(code);
};

// Chrome names the address of the protocol on its error output
const endpoint = await new Promise((resolve, reject) => {
  let text = '';
  browser.stderr.on('data', data => {
    text += data;
    const found = text.match(/DevTools listening on ws:\/\/([^/\s]+)\//);
    if (found) resolve(found[1]);
  });
  browser.on('exit', code =>
    reject(new Error(`Chrome exited with ${code}: ${text}`)));
});
const target = await (await fetch(`http://${endpoint}/json/new?about:blank`,
                                  { method: 'PUT' })).json();
const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise(resolve => socket.addEventListener('open', resolve));

let id = 0;
const pending = new Map();
socket.addEventListener('message', ({ data }) => {
  const message = JSON.parse(data);
  if (message.method === 'Runtime.exceptionThrown') {
    console.error(message.params.exceptionDetails.exception?.description
                  ?? message.params.exceptionDetails.text);
    finish(1);
  }
  const call = pending.get(message.id);
  if (!call) return;
  pending.delete(message.id);
  if (message.error) call.reject(new Error(message.error.message));
  else call.resolve(message.result);
});
const send = (method, params = {}) => new Promise((resolve, reject) => {
  pending.set(++id, { resolve, reject });
  socket.send(JSON.stringify({ id, method, params }));
});
// the value of the JavaScript `expression`, awaited when it's a promise
const evaluate = async expression => {
  const { result, exceptionDetails } = await send('Runtime.evaluate', {
    expression, awaitPromise: true, returnByValue: true });
  if (exceptionDetails) {
    throw new Error(exceptionDetails.exception?.description
                    ?? exceptionDetails.text);
  }
  return result.value;
};

// Load the page afresh, with nothing compiled or optimized yet.
const open = async () => {
  const loaded = new Promise(resolve => socket.addEventListener('message',
    ({ data }) => JSON.parse(data).method === 'Page.loadEventFired'
                  && resolve()));
  await send('Page.navigate',
             { url: `http://127.0.0.1:${server.address().port}/` });
  await loaded;
};

await send('Runtime.enable');
await send('Page.enable');
if (throttle > 1) {
  await send('Emulation.setCPUThrottlingRate', { rate: throttle });
}
await open();

// The function of each node of the `profile`, as name and place.
const functionOf = ({ callFrame: { functionName, url, lineNumber } }) =>
  `${functionName || '(anonymous)'} ${path.basename(url)}:${lineNumber + 1}`;

// The milliseconds of the `profile` in each function, by itself and with
// what it calls, the most first.
const tally = profile => {
  const byId = new Map(profile.nodes.map(node => [node.id, node]));
  const parent = new Map();
  for (const node of profile.nodes) {
    for (const child of node.children ?? []) parent.set(child, node.id);
  }
  const self = new Map();
  const total = new Map();
  const add = (m, k, ms) => m.set(k, (m.get(k) ?? 0) + ms);
  profile.samples.forEach((sample, i) => {
    const ms = (profile.timeDeltas[i + 1] ?? 0) / 1000;
    add(self, functionOf(byId.get(sample)), ms);
    const seen = new Set();
    for (let at = sample; at !== undefined; at = parent.get(at)) {
      seen.add(functionOf(byId.get(at)));
    }
    for (const f of seen) add(total, f, ms);
  });
  const sorted = m => [...m].sort((a, b) => b[1] - a[1]);
  return { self: sorted(self), total: sorted(total) };
};

try {
  if (cold) {
    console.log(`The first ${cold} parses with rss/parse in a fresh page, ms`);
    for (const feed of feeds) {
      await open();
      const times = JSON.parse(await evaluate(
        `browser_bench.first_runs(${JSON.stringify(feed)}, ${cold})`));
      console.log(feed.padEnd(18) +
                  times.map(ms => ms.toFixed(1).padStart(9)).join(''));
    }
  } else if (profile) {
    const [feed, stage] = profile.split(':');
    const times = Number(process.env.TIMES ?? 5);
    await evaluate(`browser_bench.repeat_stage(${JSON.stringify(feed)},
                    ${JSON.stringify(stage)}, ${warmup})`);
    await send('Profiler.enable');
    await send('Profiler.setSamplingInterval', { interval: 100 });
    await send('Profiler.start');
    await evaluate(`browser_bench.repeat_stage(${JSON.stringify(feed)},
                    ${JSON.stringify(stage)}, ${times})`);
    const { profile: recorded } = await send('Profiler.stop');
    const file = path.join(dir,
      `${feed}-${stage.replace(/\W+/g, '-')}.cpuprofile`);
    await fs.writeFile(file, JSON.stringify(recorded));
    const { self, total } = tally(recorded);
    const all = total.find(([f]) => f.startsWith('(root)'))?.[1] ?? 1;
    const show = (title, rows) => {
      console.log(`\n${title}, ms per run and share of ` +
                  `${(all / times).toFixed(0)} ms`);
      for (const [f, ms] of rows.slice(0, Number(process.env.TOP ?? 40))) {
        console.log(`${(ms / times).toFixed(1).padStart(8)} ` +
                    `${(100 * ms / all).toFixed(1).padStart(5)}%  ${f}`);
      }
    };
    show('By itself', self);
    show('With what it calls', total);
    console.log(`\nThe profile is in ${file}`);
  } else {
    const results = JSON.parse(await evaluate(
      `browser_bench.run(${JSON.stringify(feeds)}, ${warmup}, ${runs})`));
    if (process.env.OUT) {
      await fs.writeFile(process.env.OUT, JSON.stringify(results, null, 2));
    }
    const width = Math.max(...results[0].stages.map(([l]) => l.length));
    console.log(''.padEnd(width) + results.map(({ feed }) =>
      feed.slice(0, 16).padStart(18)).join(''));
    console.log('size, MB'.padEnd(width) + results.map(({ bytes }) =>
      (bytes / 1e6).toFixed(1).padStart(18)).join(''));
    console.log('items'.padEnd(width) + results.map(({ items }) =>
      String(items).padStart(18)).join(''));
    results[0].stages.forEach(([label], i) => {
      console.log(label.padEnd(width) + results.map(({ stages }) =>
        stages[i][1].toFixed(1).padStart(18)).join(''));
    });
  }
  await finish(0);
} catch (e) {
  console.error(e.message);
  await finish(1);
}
