// 考试详情页：找考试时间/成绩
import fs from 'node:fs';
import path from 'node:path';
import { createSession, parseCourses, MOOC } from './chx.mjs';

const USER = process.env.CHX_USER || '';
const PASS = process.env.CHX_PASS || '';
const OUT = 'D:/dsh-workplace/xxt/build/probe7';
const s = createSession(OUT);

const strip = (h) => h.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ')
  .replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
const attr = (tag, name) => (tag.match(new RegExp(name + '="([^"]*)"')) || [])[1] || '';

async function main() {
  const lr = await s.login(USER, PASS);
  if (!lr.ok) { console.log('login failed'); process.exit(1); }
  const cr = await s.req(`${MOOC}/mycourse/backclazzdata?view=json&getTchClazzType=1&mcode=`);
  const courses = parseCourses(cr.text);

  const found = [];
  for (const c of courses) {
    const e = await s.req(`${MOOC}/phone/moocAnalysis/examList?courseId=${c.courseId}&classId=${c.classId}`);
    const re = /<tr\b[^>]*\bdata="([^"]*)"[^>]*>([\s\S]*?)<\/tr>/g;
    let m;
    while ((m = re.exec(e.text))) {
      const tag = m[0].match(/<tr\b[^>]*>/)[0];
      found.push({ course: c.name, title: attr(tag, 'title'), status: attr(tag, 'status'), data: m[1] });
    }
    if (found.length >= 6) break;
  }
  console.log('exams found', found.length);
  for (const f of found.slice(0, 6)) console.log(`  st=${f.status} | ${f.course} | ${f.title}`);

  const pick = found.find(f => f.status === '4') || found[0];
  if (!pick) return;
  const d = await s.req(`${MOOC}${pick.data}`);
  fs.writeFileSync(path.join(OUT, 'exam_detail.html'), d.text);
  const body = strip(d.text);
  console.log(`\n=== exam detail | ${pick.course} | ${pick.title} | http=${d.status} len=${d.text.length}`);
  for (const kw of ['考试时间', '开始时间', '结束时间', '截止时间', '成绩', '得分', '已提交', '剩余']) {
    const i = body.indexOf(kw);
    if (i >= 0) console.log(`  [${kw}] ...${body.slice(Math.max(0, i - 40), i + 100)}...`);
  }
  console.log('  BODY HEAD:', body.slice(0, 500));
  const dates = [...d.text.matchAll(/\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(?::\d{2})?/g)].map(x => x[0]);
  console.log('  dates:', [...new Set(dates)].slice(0, 10).join(' , '));
  console.log('OUT', OUT);
}

main().catch(e => { console.log('FATAL', e.stack || e.message); process.exit(1); });
