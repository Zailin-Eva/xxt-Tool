// 学习通会话与请求封装（探测用）
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

export const MOOC = 'https://mooc1-api.chaoxing.com';
export const BROWSER_UA =
  'Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const SALT = 'ipL$TkeiEmfy1gTXb2XHrdLN0a@7c^vu';

export function createSession(outDir) {
  const jar = new Map();
  const uniqueId = crypto.randomUUID().replace(/-/g, '');
  if (outDir) fs.mkdirSync(outDir, { recursive: true });

  const cookieHeader = () => [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ');

  function absorb(res) {
    const list = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
    for (const raw of list) {
      const first = raw.split(';')[0];
      const i = first.indexOf('=');
      if (i > 0) jar.set(first.slice(0, i).trim(), first.slice(i + 1).trim());
    }
  }

  async function req(url, { method = 'GET', form, ua = BROWSER_UA, redirect = 'follow', save } = {}) {
    const headers = { 'User-Agent': ua, Accept: '*/*', 'Accept-Language': 'zh-CN,zh;q=0.9' };
    const ck = cookieHeader();
    if (ck) headers.Cookie = ck;
    let body;
    if (form) {
      headers['Content-Type'] = 'application/x-www-form-urlencoded';
      body = new URLSearchParams(form).toString();
    }
    const res = await fetch(url, { method, headers, body, redirect });
    absorb(res);
    const buf = Buffer.from(await res.arrayBuffer());
    const text = buf.toString('utf8');
    if (save && outDir) fs.writeFileSync(path.join(outDir, save), buf);
    return { status: res.status, headers: res.headers, text, buf, url };
  }

  function officialUa() {
    const suffix = ` (device:Pixel 6) Language/zh_CN com.chaoxing.mobile/ChaoXingStudy_3_7.0.2_android_phone_10993_264 (@Kalimdor)_${uniqueId}`;
    const digest = crypto.createHash('md5').update(suffix + SALT).digest('hex');
    return `Dalvik/2.1.0 (Linux; U; Android 14; Pixel 6 Build/UQ1A.240205.004) (schild:${digest})${suffix}`;
  }

  async function login(user, pass) {
    const key = Buffer.from('z4ok6lu^oWp4_AES', 'utf8');
    const c = crypto.createCipheriv('aes-128-ecb', key, null);
    c.setAutoPadding(true);
    const logininfo = Buffer.concat([
      c.update(JSON.stringify({ uname: user, code: pass }), 'utf8'),
      c.final(),
    ]).toString('base64');

    const r = await req('https://passport2-api.chaoxing.com/v11/loginregister?cx_xxt_passport=json', {
      method: 'POST',
      form: {
        logininfo, cfid: '', countrycode: '+86', loginType: '1', roleSelect: 'true', entype: '1',
      },
    });
    let j = {};
    try { j = JSON.parse(r.text); } catch { /* ignore */ }
    if (j.status !== true) return { ok: false, body: j, raw: r.text.slice(0, 300) };
    if (j.url) {
      let next = j.url;
      for (let hop = 0; hop < 5; hop++) {
        const s = await req(next, { method: 'POST', form: {}, redirect: 'manual' });
        const loc = s.headers.get('location');
        if ([301, 302, 303, 307].includes(s.status) && loc) {
          next = new URL(loc, next).toString();
          continue;
        }
        break;
      }
    }
    await req('https://i.chaoxing.com');
    return { ok: jar.has('_uid') || jar.has('_d'), uid: jar.get('_uid') || '', jar };
  }

  return { req, login, officialUa, jar, cookieHeader };
}

export function parseCourses(text) {
  const out = [];
  let j;
  try { j = JSON.parse(text); } catch { return out; }
  for (const ch of j.channelList || []) {
    let content = ch.content;
    if (typeof content === 'string') { try { content = JSON.parse(content); } catch { continue; } }
    if (!content) continue;
    const cd = content.course && content.course.data;
    if (Array.isArray(cd) && cd.length) {
      for (const c of cd) out.push({ courseId: String(c.id), classId: String(content.id), name: c.name, cpi: content.cpi });
    } else if (content.id) {
      for (const c of content.clazz || []) out.push({ courseId: String(content.id), classId: String(c.id), name: content.name, cpi: content.cpi });
    }
  }
  const seen = new Set();
  return out.filter(c => {
    const k = c.courseId + '_' + c.classId;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}
