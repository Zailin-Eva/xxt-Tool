import fs from 'node:fs';
import { createSession, parseCourses, MOOC } from './chx.mjs';
const s = createSession('D:/dsh-workplace/xxt/build/probe8');
const lr = await s.login(process.env.CHX_USER, process.env.CHX_PASS);
if (!lr.ok) { console.log('login failed'); process.exit(1); }
const cr = await s.req(`${MOOC}/mycourse/backclazzdata?view=json&getTchClazzType=1&mcode=`);
const courses = parseCourses(cr.text);
let found = null;
for (const c of courses) {
  const e = await s.req(`${MOOC}/phone/moocAnalysis/examList?courseId=${c.courseId}&classId=${c.classId}`);
  const m = e.text.match(/<tr\b[^>]*\bdata="([^"]*)"[^>]*status="2"[^>]*>/);
  if (m) { found = { course: c.name, data: m[1] }; break; }
}
if (!found) { console.log('no expired exam'); process.exit(0); }
const d = await s.req(`${MOOC}${found.data}`);
fs.writeFileSync('D:/dsh-workplace/xxt/build/probe8/exam_expired.html', d.text);
const txt = d.text.replace(/<script[\s\S]*?<\/script>/gi,' ').replace(/<style[\s\S]*?<\/style>/gi,' ').replace(/<[^>]+>/g,' ').replace(/&nbsp;/g,' ').replace(/\s+/g,' ');
console.log('course:', found.course, 'len:', d.text.length);
console.log('body:', txt.slice(0, 600));
const dates = [...d.text.matchAll(/\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}/g)].map(x=>x[0]);
console.log('dates:', [...new Set(dates)].slice(0,8).join(' , '));
