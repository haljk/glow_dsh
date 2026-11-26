// 假队列服务：给 Android 端验证用，不连真模型。
//
// 它做的事很少：收任务、按时间把状态从排队推到成功、回一张真 PNG、按状态回答取消。
// 之所以要有它：本轮要验的是「客户端重启/断网/停止等待后能不能按任务号把结果拿回来」，
// 这条链路跟模型有没有真的画图无关，用真服务反而要等 2-6 分钟、还会排队。
//
// 用法：
//   node tools/stub-queue.mjs [port]        默认 9742
//   POST /__config {"queuedMs":6000,"runningMs":20000,"failMode":"ok|fail500|gone"}
//   GET  /__state                           看每个任务的 prompt（验证直发原文用）
//   POST /__reset                           清空任务与配置
//
// 模拟器访问宿主用 10.0.2.2:<port>，真机用宿主局域网 IP。

import http from 'node:http';
import zlib from 'node:zlib';

const PORT = Number(process.argv[2] || 9742);

/** @type {Map<string, {id:string,prompt:string,seed:number,size:string,createdAt:number,status:string,canceled:boolean,error:string,failed:boolean}>} */
const jobs = new Map();
const config = { queuedMs: 6000, runningMs: 20000, failMode: 'ok' };

let counter = 0;

function makePng(width = 64, height = 64) {
  const raw = Buffer.alloc((width * 3 + 1) * height);
  let at = 0;
  for (let y = 0; y < height; y++) {
    raw[at++] = 0; // filter: none
    for (let x = 0; x < width; x++) {
      raw[at++] = 40 + (x * 3) % 200;
      raw[at++] = 40 + (y * 5) % 200;
      raw[at++] = 120;
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'latin1'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // truecolor
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

let crcTable = null;
function crc32(buf) {
  if (!crcTable) {
    crcTable = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c;
    }
  }
  let c = 0xffffffff;
  for (const byte of buf) c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8);
  return c ^ 0xffffffff;
}

const PNG = makePng();

function nowIso() {
  return new Date().toISOString();
}

function evolve(job) {
  if (job.canceled) return 'canceled';
  if (job.failed) return 'failed';
  const age = Date.now() - job.createdAt;
  if (age < config.queuedMs) return 'queued';
  if (age < config.queuedMs + config.runningMs) return 'running';
  return 'succeeded';
}

function statePayload(job) {
  const status = evolve(job);
  const age = Date.now() - job.createdAt;
  const span = Math.max(1, config.queuedMs + config.runningMs);
  const percent = status === 'succeeded' ? 100 : Math.min(95, Math.round((age / span) * 100));
  const payload = {
    id: job.id,
    object: 'image.job',
    status,
    created_at: job.createdAt,
    prompt: job.prompt,
    progress: {
      percent: status === 'queued' ? 0 : percent,
      eta_seconds: Math.max(0, Math.round((span - age) / 1000)),
      queue_position: status === 'queued' ? 1 : 0,
    },
    error: status === 'failed' ? job.error || '上游这次没跑成功' : '',
    http_status: status === 'failed' ? 500 : 200,
  };
  if (status === 'succeeded') {
    const base = `http://${job.host}/v1/images/jobs/${job.id}`;
    payload.data = [
      {
        url: `${base}/result.png`,
        size: job.size,
        seed: job.seed,
        revised_prompt: '',
      },
    ];
  }
  return payload;
}

/** multipart 里只取 prompt / size / seed 三个字段，够用就行。 */
function parseMultipart(body, contentType) {
  const boundary = /boundary=(.+)$/.exec(contentType || '');
  if (!boundary) return {};
  const parts = body.toString('latin1').split(`--${boundary[1].trim()}`);
  const fields = {};
  for (const part of parts) {
    const nameMatch = /name="([^"]+)"/.exec(part);
    if (!nameMatch) continue;
    const split = part.indexOf('\r\n\r\n');
    if (split < 0) continue;
    let value = part.slice(split + 4);
    if (value.endsWith('\r\n')) value = value.slice(0, -2);
    fields[nameMatch[1]] = value;
  }
  return fields;
}

function send(res, code, json) {
  const body = Buffer.from(JSON.stringify(json, null, 2));
  res.writeHead(code, { 'content-type': 'application/json', 'content-length': body.length });
  res.end(body);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const path = url.pathname;
  const host = req.headers.host || `localhost:${PORT}`;
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks);

    if (path === '/health') {
      return send(res, 200, {
        status: 'ok',
        model: 'stub-queue',
        upstream: { ok: true, url: 'http://127.0.0.1:9741' },
        queue: { queued: 0, running: 0, max: 64 },
      });
    }

    if (path === '/__config' && req.method === 'POST') {
      Object.assign(config, JSON.parse(body.toString() || '{}'));
      return send(res, 200, config);
    }

    if (path === '/__reset' && req.method === 'POST') {
      jobs.clear();
      Object.assign(config, { queuedMs: 6000, runningMs: 20000, failMode: 'ok' });
      return send(res, 200, { ok: true });
    }

    if (path === '/__state') {
      return send(res, 200, {
        config,
        jobs: [...jobs.values()].map((job) => ({
          id: job.id,
          prompt: job.prompt,
          size: job.size,
          seed: job.seed,
          status: evolve(job),
          createdAt: job.createdAt,
        })),
      });
    }

    if (path === '/v1/images/jobs' && req.method === 'POST') {
      let parsed = {};
      try {
        parsed = JSON.parse(body.toString() || '{}');
      } catch {
        return send(res, 400, { error: '请求体不是 JSON' });
      }
      const prompt = String(parsed.prompt ?? '');
      if (!prompt.trim()) return send(res, 400, { error: 'prompt 不能为空' });
      const job = {
        id: `job_stub_${String(++counter).padStart(4, '0')}`,
        prompt,
        size: String(parsed.size ?? '1024x1024'),
        seed: Number(parsed.seed ?? 0),
        createdAt: Date.now(),
        canceled: false,
        failed: false,
        error: '',
        host,
      };
      jobs.set(job.id, job);
      return send(res, 202, statePayload(job));
    }

    if (path === '/v1/images/jobs/edits' && req.method === 'POST') {
      const fields = parseMultipart(body, req.headers['content-type']);
      const prompt = String(fields.prompt ?? '');
      if (!prompt.trim()) return send(res, 400, { error: 'prompt 不能为空' });
      const job = {
        id: `job_stub_${String(++counter).padStart(4, '0')}`,
        prompt,
        size: String(fields.size ?? '1024x1024'),
        seed: Number(fields.seed ?? 0),
        createdAt: Date.now(),
        canceled: false,
        failed: false,
        error: '',
        host,
      };
      jobs.set(job.id, job);
      return send(res, 202, statePayload(job));
    }

    const jobMatch = /^\/v1\/images\/jobs\/([^/]+)(\/.*)?$/.exec(path);
    if (jobMatch) {
      const job = jobs.get(jobMatch[1]);
      if (!job) return send(res, 404, { error: '没有这个任务' });
      const rest = jobMatch[2] || '';

      if (rest === '' && req.method === 'GET') {
        if (config.failMode === 'fail500') return send(res, 500, { error: '服务端暂时不可用（模拟）' });
        if (config.failMode === 'gone') return send(res, 404, { error: '没有这个任务（模拟）' });
        return send(res, 200, statePayload(job));
      }

      if (rest === '/cancel' && req.method === 'POST') {
        const status = evolve(job);
        if (status === 'queued') {
          job.canceled = true;
          return send(res, 200, statePayload(job));
        }
        return send(res, 409, { error: `任务已经在 ${status}，停不下来` });
      }

      if (/^\/result(-\d+)?\.png$/.test(rest) && req.method === 'GET') {
        const status = evolve(job);
        if (status !== 'succeeded') return send(res, 409, { error: '还没跑完' });
        res.writeHead(200, { 'content-type': 'image/png', 'content-length': PNG.length });
        return res.end(PNG);
      }
      return send(res, 404, { error: '不认识的路径' });
    }

    return send(res, 404, { error: '不认识的路径', path });
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`stub queue on http://0.0.0.0:${PORT}  (emulator: http://10.0.2.2:${PORT})`);
  console.log(`started ${nowIso()}`);
});
