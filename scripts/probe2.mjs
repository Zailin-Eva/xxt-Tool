// 全量探测：所有课程的作业/考试列表 + 作业详情页
import fs from 'node:fs';
import path from 'node:path';
import { createSession, parseCourses, MOOC } from './chx.mjs';

const USER = process.env.CHX_USER || '';
const PASS = process.env.CHX_PASS || '';
const OUT = process.env.CHX_OUT || 'D:/dsh-workplace/xxt/build/probe2';

const s = createSession(OUT);

const strip = (h) => h.replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
const attr = (tag, name) => {
  const m = tag.match(new RegExp(name + '="([^"]*)"'));
  return m ? m[1] : '';
};

function parseWork(html, course) {
  const out = [];
  const re = /<dt\b[^>]*>([\s\S]*?)<\/dt>/g;
  let m;
  while ((m = re.exec(html))) {
    const tag = m[0].match(/<dt\b[^>]*>/)[0];
    const data = html.includes(tag) ? attr(tag, 'data') : '';
    if (!data) continue;
    const inner = m[1];
    const h3 = inner.match(/<h3[^>]*>([\s\S]*?)<\/h3>/);
    const p = inner.match(/<p[^>]*>([\s\S]*?)<\/p>/);
    const b = inner.match(/<b[^>]*>([\s\S]*?)<\/b>/);
    out.push({
      course: course.name, courseId: course.courseId, classId: course.classId,
      title: h3 ? strip(h3[1]) : '',
      date: p ? strip(p[1]) : '',
      action: b ? strip(b[1]) : '',
      status: attr(tag, 'status'),
      data,
    });
  }
  return out;
}

function parseExam(html, course) {
  const out = [];
  const re = /<tr\b[^>]*\bdata="([^"]*)"[^>]*>([\s\S]*?)<\/tr>/g;
  let m;
  while ((m = re.exec(html))) {
    const tag = m[0].match(/<tr\b[^>]*>/)[0];
    out.push({
      course: course.name, title: attr(tag, 'title'), status: attr(tag, 'status'), data: m[1],
    });
  }
  return out;
}

async function main() {
  const lr = await s.login(USER, PASS);
  if (!lr.ok) { console.log('login failed', lr.raw || ''); process.exit(1); }
  console.log('login ok uid=', lr.uid);

  const cr = await s.req(`${MOOC}/mycourse/backclazzdata?view=json&getTchClazzType=1&mcode=`);
  const courses = parseCourses(cr.text);
  console.log('courses', courses.length);

  const works = [];
  const exams = [];
  const examEmpty = [];
  const examNonEmpty = [];

  for (let i = 0; i < courses.length; i += 4) {
    const batch = courses.slice(i, i + 4);
    await Promise.all(batch.map(async (c) => {
      const w = await s.req(`${MOOC}/phone/moocAnalysis/workList?courseId=${c.courseId}&classId=${c.classId}`);
      works.push(...parseWork(w.text, c));
      const e = await s.req(`${MOOC}/phone/moocAnalysis/examList?courseId=${c.courseId}&classId=${c.classId}`);
      if (e.text.includes('暂无收到考试') || e.text.includes('尚未收到考试')) examEmpty.push(c.name);
      else { examNonEmpty.push(c.name); exams.push(...parseExam(e.text, c)); fs.writeFileSync(path.join(OUT, `exam_ne_${c.courseId}.html`), e.text); }
    }));
  }

  console.log('work items', works.length, 'exam items', exams.length);
  console.log('exam empty courses', examEmpty.length, 'non-empty', examNonEmpty.length);
  console.log('exam non-empty names:', examNonEmpty.slice(0, 10).join(' | '));

  const stat = {};
  for (const w of works) stat[w.status + '/' + w.action] = (stat[w.status + '/' + w.action] || 0) + 1;
  console.log('work status/action distribution:', JSON.stringify(stat, null, 0));

  console.log('--- sample works ---');
  for (const w of works.slice(0, 5)) {
    console.log(`st=${w.status} act=${w.action} date=${w.date} | ${w.course} | ${w.title}`);
  }

  fs.writeFileSync(path.join(OUT, 'works.json'), JSON.stringify(works, null, 1));
  fs.writeFileSync(path.join(OUT, 'exams.json'), JSON.stringify(exams, null, 1));

  // 详情页：找截止时间
  const targets = works.filter(w => w.status === '1').slice(0, 3);
  for (const t of targets) {
    const url = t.data.startsWith('http') ? t.data : `${MOOC}${t.data.startsWith('/') ? '' : '/'}${t.data}`;
    const d = await s.req(url);
    fs.writeFileSync(path.join(OUT, `detail_${t.title.slice(0, 8).replace(/[\\/:*?"<>|\s]/g, '_')}.html`), d.text);
    const body = strip(d.text);
    const idx = body.indexOf('截止');
    console.log(`[detail] ${t.title} http=${d.status} len=${d.text.length} 截止@${idx}`);
    if (idx >= 0) console.log('   ...', body.slice(Math.max(0, idx - 60), idx + 120));
    const si = body.indexOf('提交时间');
    if (si >= 0) console.log('   提交:', body.slice(si, si + 60));
  }

  console.log('OUT', OUT);
}

main().catch(e => { console.log('FATAL', e.stack || e.message); process.exit(1); });
