// 试候选接口，找「提交时间/批阅时间」
import { createSession, parseCourses, MOOC } from './chx.mjs';

const USER = process.env.CHX_USER || '';
const PASS = process.env.CHX_PASS || '';
const s = createSession('D:/dsh-workplace/xxt/build/probe4');

const attr = (tag, name) => (tag.match(new RegExp(name + '="([^"]*)"')) || [])[1] || '';

async function main() {
  const lr = await s.login(USER, PASS);
  if (!lr.ok) { console.log('login failed'); process.exit(1); }
  const cr = await s.req(`${MOOC}/mycourse/backclazzdata?view=json&getTchClazzType=1&mcode=`);
  const courses = parseCourses(cr.text);

  // 找一门既有待批阅(3)又有已完成(4)的课
  let target = null;
  for (const c of courses) {
    const w = await s.req(`${MOOC}/phone/moocAnalysis/workList?courseId=${c.courseId}&classId=${c.classId}`);
    const sts = [...w.text.matchAll(/status="(\d)"/g)].map(m => m[1]);
    if (sts.includes('3') || sts.includes('4')) { target = { ...c, html: w.text }; break; }
  }
  if (!target) { console.log('no target course'); return; }
  console.log('target', target.name, target.courseId, target.classId);

  const cid = target.courseId, lid = target.classId;
  const urls = [
    `${MOOC}/api/phone/jobfinishedList?courseid=${cid}&clazzid=${lid}&knowledgeid=&jobid=&page=1&pageSize=20&view=json`,
    `${MOOC}/api/phone/jobunfinishedList?courseid=${cid}&clazzid=${lid}&knowledgeid=&jobid=&page=1&pageSize=20&view=json`,
    `${MOOC}/api/task-status?classId=${lid}&qtype=work,exam&view=json`,
    `${MOOC}/work/list?courseId=${cid}&classId=${lid}&ut=s&status=3`,
  ];
  for (const u of urls) {
    const r = await s.req(u);
    console.log(`\n=== ${u.replace(MOOC, '')} -> ${r.status} len=${r.text.length}`);
    console.log(r.text.slice(0, 500).replace(/\s+/g, ' '));
  }
}

main().catch(e => { console.log('FATAL', e.stack || e.message); process.exit(1); });
