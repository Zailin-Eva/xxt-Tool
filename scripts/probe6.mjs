// 试 PC 版(mooc2-ans) 作业列表页，拿「提交时间」
import fs from 'node:fs';
import path from 'node:path';
import { createSession, parseCourses, MOOC } from './chx.mjs';

const USER = process.env.CHX_USER || '';
const PASS = process.env.CHX_PASS || '';
const OUT = 'D:/dsh-workplace/xxt/build/probe6';
const s = createSession(OUT);

async function main() {
  const lr = await s.login(USER, PASS);
  if (!lr.ok) { console.log('login failed'); process.exit(1); }
  const cr = await s.req(`${MOOC}/mycourse/backclazzdata?view=json&getTchClazzType=1&mcode=`);
  const courses = parseCourses(cr.text);

  // 取一门本学期、作业最多的课
  const cand = courses.filter(c => /2026/.test(c.name));
  const c = cand[0] || courses[0];
  console.log('course', c.name, c.courseId, c.classId, 'cpi', c.cpi);

  const urls = [
    `https://mooc2-ans.chaoxing.com/mooc2-ans/mooc2/work/list?courseId=${c.courseId}&classId=${c.classId}&cpi=${c.cpi}&ut=s&enc=`,
    `https://mooc1.chaoxing.com/mooc2/work/list?courseId=${c.courseId}&classId=${c.classId}&cpi=${c.cpi}&ut=s&enc=`,
    `https://mooc1.chaoxing.com/visit/courselistdata`,
    `https://mooc1.chaoxing.com/visit/interaction`,
  ];
  for (const u of urls) {
    const r = await s.req(u);
    const name = u.includes('courselistdata') ? 'courselistdata.html'
      : u.includes('interaction') ? 'interaction.html'
      : u.includes('mooc2-ans') ? 'worklist_mooc2ans.html' : 'worklist_mooc1.html';
    fs.writeFileSync(path.join(OUT, name), r.text);
    const hasSubmit = r.text.includes('提交时间');
    const hasEnc = /enc=([0-9a-f]{16,})/i.test(r.text);
    console.log(`${u.slice(0, 70)} -> ${r.status} len=${r.text.length} 提交时间=${hasSubmit} encInPage=${hasEnc}`);
    if (r.status === 200 && r.text.length > 2000) {
      const i = r.text.indexOf('提交时间');
      if (i >= 0) console.log('   ctx:', r.text.slice(Math.max(0, i - 300), i + 200).replace(/\s+/g, ' '));
      const e = r.text.match(/enc=([0-9a-f]{16,})/i);
      if (e) console.log('   enc:', e[1]);
    }
  }
  console.log('OUT', OUT);
}

main().catch(e => { console.log('FATAL', e.stack || e.message); process.exit(1); });
