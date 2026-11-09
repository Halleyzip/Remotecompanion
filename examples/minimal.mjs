// 最小示例：绑定一个玩家、让她跟随你、并说一句话
// 用法：node examples/minimal.mjs [身体玩家名] [跟随玩家名]
const BASE = 'http://127.0.0.1:3232';

async function post(path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body ?? {}),
  });
  return res.text();
}

const body = process.argv[2] ?? 'Companion';
const owner = process.argv[3] ?? 'YourName';

console.log('status  :', await (await fetch(BASE + '/status')).text());
console.log('bind    :', await post('/player', { name: body }));
console.log('follow  :', await post('/follow', { name: owner }));
console.log('say     :', await post('/say', { text: '我来了，你在哪' }));
console.log('status  :', await (await fetch(BASE + '/status')).text());
