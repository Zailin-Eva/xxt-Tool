// 按状态取样，抓详情页，确认状态码含义
import fs from 'node:fs';
import path from 'node:path';
import { createSession, parseCourses, MOOC } from './chx.mjs';

const USER = process.env.CHX_USER || '';
const PASS = process.env.CHX_PASS || '';
const OUT = process.env.CHX_OUT || 'D:/dsh-workplace/xxt/build/probe3';
const s = createSession(OUT);

const strip = (h) => h.replace(/<script[\s\S]*?<\/script>/gi, ' ')
  .replace(/<style[\s\S]*?<\/style>/gi, ' ')
  .replace(/<[^>]+>/g, ' ')
  .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
  .replace(/\s+/g, ' ').trim();
const attr = (tag, name) => (tag.match(new RegExp(name + '="([^"]*)"')) || [])[1] || '';

function parseWork(html, course) {
  const out = [];
  const re = /<dt\b[^>]*>([\s\S]*?)<\/dt>/g;
  let m;
  while ((m = re.exec(html))) {
    const tag = m[0].match(/<dt\b[^>]*>/)[0];
    const data = attr(tag, 'data');
    if (!data) continue;
    const inner = m[1];
    const h3 = inner.match(/<h3[^>]*>([\s\S]*?)<\/h3>/);
    const b = inner.match(/<b[^>]*>([\s\S]*?)<\/b>/);
    out.push({
      course: course.name, courseId: course.courseId, classId: course.classId,
      title: h3 ? strip(h3[1]) : '', action: b ? strip(b[1]) : '',
      status: attr(tag, 'status'), data,
    });
  }
  return out;
}

async function main() {
  const lr = await s.login(USER, PASS);
  if (!lr.ok) { console.log('login failed'); process.exit(1); }
  const cr = await s.req(`${MOOC}/mycourse/backclazzdata?view=json&getTchClazzType=1&mcode=`);
  const courses = parseCourses(cr.text);

  // 只看本学期的课（名字里带 2026 的），避免旧课干扰
  const cur = courses.filter(c => /2026|春季|秋季/.test(c.name));
  console.log('current-ish courses', cur.length, cur.map(c => c.name).join(' | '));

  const works = [];
  for (const c of courses) {
    const w = await s.req(`${MOOC}/phone/moocAnalysis/workList?courseId=${c.courseId}&classId=${c.classId}`);
    works.push(...parseWork(w.text, c));
  }
  const byStatus = {};
  for (const w of works) (byStatus[w.status] ||= []).push(w);
  console.log('status counts', Object.fromEntries(Object.entries(byStatus).map(([k, v]) => [k, v.length])));

  for (const st of Object.keys(byStatus)) {
    const t = byStatus[st][0];
    const url = `${MOOC}${t.data}`;
    const d = await s.req(url);
    const body = strip(d.text);
    fs.writeFileSync(path.join(OUT, `detail_status${st}.html`), d.text);
    console.log(`\n=== status=${st} | ${t.course} | ${t.title} | http=${d.status} len=${d.text.length}`);
    for (const kw of ['开始时间', '截止时间', '提交时间', '批阅', '得分', '成绩', '状态']) {
      const i = body.indexOf(kw);
      if (i >= 0) console.log(`  [${kw}] ...${body.slice(Math.max(0, i - 40), i + 80)}...`);
    }
    const start = body.indexOf('开始时间');
    if (start >= 0) console.log('  HEAD:', body.slice(Math.max(0, start - 120), start + 160));
  }

  // 考试详情
  const examRow = { title: '', data: '' };
  for (const c of cur) {
    const e = await s.req(`${MOOC}/phone/moocAnalysis/examList?courseId=${c.courseId}&classId=${c.classId}`);
    const m = e.text.match(/<tr\b[^>]*\bdata="([^"]*)"[^>]*>/);
    if (m) { examRow.data = m[1]; examRow.title = attr(m[0], 'title'); examRow.course = c.name; break; }
  }
  if (examRow.data) {
    const d = await s.req(`${MOOC}${examRow.data}`);
    fs.writeFileSync(path.join(OUT, 'exam_detail.html'), d.text);
    const body = strip(d.text);
    console.log(`\n=== exam detail | ${examRow.course} | ${examRow.title} | http=${d.status} len=${d.text.length}`);
    for (const kw of ['考试时间', '开始时间', '截止时间', '成绩', '得分', '状态', '已交卷']) {
      const i = body.indexOf(kw);
      if (i >= 0) console.log(`  [${kw}] ...${body.slice(Math.max(0, i - 40), i + 100)}...`);
    }
    console.log('  HEAD:', body.slice(0, 400));
  }
  console.log('\nOUT', OUT);
}

main().catch(e => { console.log('FATAL', e.stack || e.message); process.exit(1); });
