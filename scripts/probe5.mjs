// 抓「查看详情」答卷页（selectWorkQuestionYiPiYue），找提交时间/得分
import fs from 'node:fs';
import path from 'node:path';
import { createSession, parseCourses, MOOC } from './chx.mjs';

const USER = process.env.CHX_USER || '';
const PASS = process.env.CHX_PASS || '';
const OUT = 'D:/dsh-workplace/xxt/build/probe5';
const s = createSession(OUT);

const strip = (h) => h.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ')
  .replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
const attr = (tag, name) => (tag.match(new RegExp(name + '="([^"]*)"')) || [])[1] || '';

function parseWork(html, course) {
  const out = [];
  const re = /<dt\b[^>]*>([\s\S]*?)<\/dt>/g;
  let m;
  while ((m = re.exec(html))) {
    const tag = m[0].match(/<dt\b[^>]*>/)[0];
    const data = attr(tag, 'data'); if (!data) continue;
    const h3 = m[1].match(/<h3[^>]*>([\s\S]*?)<\/h3>/);
    out.push({ course: course.name, title: h3 ? strip(h3[1]) : '', status: attr(tag, 'status'), data });
  }
  return out;
}

async function main() {
  const lr = await s.login(USER, PASS);
  if (!lr.ok) { console.log('login failed'); process.exit(1); }
  const cr = await s.req(`${MOOC}/mycourse/backclazzdata?view=json&getTchClazzType=1&mcode=`);
  const courses = parseCourses(cr.text);

  const picked = {};
  for (const c of courses) {
    if (picked['3'] && picked['4']) break;
    const w = await s.req(`${MOOC}/phone/moocAnalysis/workList?courseId=${c.courseId}&classId=${c.classId}`);
    for (const it of parseWork(w.text, c)) {
      if (!picked[it.status] && (it.status === '3' || it.status === '4')) picked[it.status] = it;
    }
  }
  for (const [st, it] of Object.entries(picked)) {
    const d = await s.req(`${MOOC}${it.data}`);
    const m = d.text.match(/var url = location\.origin \+ _CP_ \+ '([^']+)'/);
    console.log(`\n=== status=${st} ${it.course} | ${it.title}`);
    if (!m) { console.log('  no answer url'); continue; }
    const ansUrl = `${MOOC}/mooc-ans${m[1]}`;
    const a = await s.req(ansUrl);
    fs.writeFileSync(path.join(OUT, `answer_status${st}.html`), a.text);
    const body = strip(a.text);
    console.log(`  answer http=${a.status} len=${a.text.length}`);
    for (const kw of ['提交时间', '批阅时间', '得分', '成绩', '已批阅', '等待教师批阅', '提交']) {
      const i = body.indexOf(kw);
      if (i >= 0) console.log(`  [${kw}] ...${body.slice(Math.max(0, i - 50), i + 90)}...`);
    }
    const dates = [...a.text.matchAll(/\d{4}-\d{2}-\d{2}[/ T]\d{2}:\d{2}(?::\d{2})?/g)].map(x => x[0]);
    console.log('  dates found:', [...new Set(dates)].slice(0, 8).join(' , '));
  }
  console.log('\nOUT', OUT);
}

main().catch(e => { console.log('FATAL', e.stack || e.message); process.exit(1); });
