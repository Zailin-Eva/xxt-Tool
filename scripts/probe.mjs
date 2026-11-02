// 学习通接口探测：登录并抓取原始响应，用于校准解析逻辑。
// 凭据从环境变量读取，不落盘。
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const USER = process.env.CHX_USER || '';
const PASS = process.env.CHX_PASS || '';
const OUT = process.env.CHX_OUT || 'D:/dsh-workplace/xxt/build/probe';

const BROWSER_UA =
  'Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const MOOC = 'https://mooc1-api.chaoxing.com';
const SALT = 'ipL$TkeiEmfy1gTXb2XHrdLN0a@7c^vu';

const jar = new Map();
let uniqueId = crypto.randomUUID().replace(/-/g, '');

function cookieHeader() {
  return [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
}

function absorb(res) {
  const list = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
  for (const raw of list) {
    const first = raw.split(';')[0];
    const i = first.indexOf('=');
    if (i > 0) jar.set(first.slice(0, i).trim(), first.slice(i + 1).trim());
  }
}

async function req(url, { method = 'GET', form, ua = BROWSER_UA, redirect = 'follow' } = {}) {
  const headers = {
    'User-Agent': ua,
    'Accept': '*/*',
    'Accept-Language': 'zh-CN,zh;q=0.9',
  };
  const ck = cookieHeader();
  if (ck) headers['Cookie'] = ck;
  let body;
  if (form) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    body = new URLSearchParams(form).toString();
  }
  const res = await fetch(url, { method, headers, body, redirect });
  absorb(res);
  const text = await res.text();
  return { status: res.status, headers: res.headers, text, url };
}

function officialUa() {
  const suffix = ` (device:Pixel 6) Language/zh_CN com.chaoxing.mobile/ChaoXingStudy_3_7.0.2_android_phone_10993_264 (@Kalimdor)_${uniqueId}`;
  const digest = crypto.createHash('md5').update(suffix + SALT).digest('hex');
  return `Dalvik/2.1.0 (Linux; U; Android 14; Pixel 6 Build/UQ1A.240205.004) (schild:${digest})${suffix}`;
}

function encLogin(user, pass) {
  const key = Buffer.from('z4ok6lu^oWp4_AES', 'utf8');
  const plain = JSON.stringify({ uname: user, code: pass });
  const c = crypto.createCipheriv('aes-128-ecb', key, null);
  c.setAutoPadding(true);
  return Buffer.concat([c.update(plain, 'utf8'), c.final()]).toString('base64');
}

async function login() {
  const r = await req('https://passport2-api.chaoxing.com/v11/loginregister?cx_xxt_passport=json', {
    method: 'POST',
    form: {
      logininfo: encLogin(USER, PASS),
      cfid: '',
      countrycode: '+86',
      loginType: '1',
      roleSelect: 'true',
      entype: '1',
    },
  });
  let j = {};
  try { j = JSON.parse(r.text); } catch { /* ignore */ }
  console.log('[login] http', r.status, 'status=', j.status, 'hasUrl=', !!j.url, 'msg=', j.mes || j.msg || '');
  if (j.status !== true) {
    console.log('[login] raw:', r.text.slice(0, 400));
    return false;
  }
  // 跟随 SSO 换身份 Cookie
  if (j.url) {
    let next = j.url;
    for (let hop = 0; hop < 5; hop++) {
      const s = await req(next, { method: 'POST', form: {}, redirect: 'manual' });
      const loc = s.headers.get('location');
      console.log(`[sso] hop${hop} ${s.status} -> ${loc ? 'redirect' : 'done'}`);
      if ((s.status === 301 || s.status === 302 || s.status === 303 || s.status === 307) && loc) {
        next = new URL(loc, next).toString();
        continue;
      }
      break;
    }
  }
  await req('https://i.chaoxing.com');
  console.log('[cookies]', [...jar.keys()].join(','));
  return jar.has('_uid') || jar.has('_d');
}

function save(name, text) {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, name), text, 'utf8');
}

async function main() {
  if (!USER || !PASS) { console.log('missing CHX_USER / CHX_PASS'); process.exit(2); }
  const ok = await login();
  if (!ok) { console.log('[abort] login failed'); process.exit(1); }

  const cl = await req(`${MOOC}/mycourse/backclazzdata?view=json&getTchClazzType=1&mcode=`, {
    ua: BROWSER_UA,
  });
  save('courses.json', cl.text);
  console.log('[courses] http', cl.status, 'len', cl.text.length);

  let courses = [];
  try {
    const j = JSON.parse(cl.text);
    const chs = j.channelList || [];
    for (const ch of chs) {
      let content = ch.content;
      if (typeof content === 'string') { try { content = JSON.parse(content); } catch { continue; } }
      if (!content) continue;
      const courseData = content.course && content.course.data;
      if (Array.isArray(courseData) && courseData.length) {
        for (const c of courseData) {
          courses.push({ courseId: String(c.id), classId: String(content.id), name: c.name, cpi: content.cpi });
        }
      } else if (content.id) {
        const cls = content.clazz || [];
        for (const c of cls) {
          courses.push({ courseId: String(content.id), classId: String(c.id), name: content.name, cpi: content.cpi });
        }
      }
    }
  } catch (e) { console.log('[courses] parse error', e.message); }
  console.log('[courses] count', courses.length, 'first:', courses.slice(0, 3).map(c => c.name).join(' | '));

  for (const c of courses.slice(0, 4)) {
    const w = await req(`${MOOC}/phone/moocAnalysis/workList?courseId=${c.courseId}&classId=${c.classId}`);
    save(`work_${c.courseId}.html`, w.text);
    const e = await req(`${MOOC}/phone/moocAnalysis/examList?courseId=${c.courseId}&classId=${c.classId}`);
    save(`exam_${c.courseId}.html`, e.text);
    console.log(`[${c.name}] work=${w.status}/${w.text.length}B exam=${e.status}/${e.text.length}B`);
  }

  const fields = 'id,userid,taskrefid,classid,courseid,taskcreatetime,taskopentime,taskendtime,status,type,coursename,coursecover,title,dotaskurl,clientexam,finishstandard';
  const uid = jar.get('_uid') || '';
  const tf = await req(`${MOOC}/gas/taskmsg?id=&userid=${uid}&type=&limit=100&offset=0&order=time&orderway=desc&fields=${fields}&view=json`, { ua: officialUa() });
  save('taskmsg.json', tf.text);
  console.log('[taskmsg] http', tf.status, 'len', tf.text.length, 'head:', tf.text.slice(0, 160).replace(/\s+/g, ' '));

  console.log('[done] files in', OUT);
}

main().catch(e => { console.log('FATAL', e.message); process.exit(1); });
