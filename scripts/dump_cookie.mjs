// 登录并把 Cookie 写到文件，供 debug 包注入（不打印凭据）
import fs from 'node:fs';
import { createSession } from './chx.mjs';

const s = createSession('');
const lr = await s.login(process.env.CHX_USER || '', process.env.CHX_PASS || '');
if (!lr.ok) { console.log('login failed', lr.raw || ''); process.exit(1); }
const out = process.env.OUT || 'D:/dsh-workplace/xxt/build/cookie.txt';
fs.mkdirSync(out.replace(/[\\/][^\\/]+$/, ''), { recursive: true });
fs.writeFileSync(out, `cookie=${s.cookieHeader()}\nuid=${lr.uid}\n`, 'utf8');
console.log('cookie written, uid=', lr.uid, 'len=', s.cookieHeader().length);
