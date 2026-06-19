const HEADERS = {
  'Content-Type': 'application/json',
  'Accept': 'application/json',
  'Origin': 'https://www.10bis.co.il',
  'Referer': 'https://www.10bis.co.il/',
  'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36',
};

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  if (req.method === 'OPTIONS') return res.status(200).end();

  const { email } = req.body;
  const r = await fetch(
    'https://www.10bis.co.il/NextApi/GetUserAuthenticationDataAndSendAuthenticationCodeToUser',
    { method: 'POST', headers: HEADERS, body: JSON.stringify({ culture: 'he-IL', uiCulture: 'he', email }) }
  );
  const data = await r.json();
  res.json(data);
}
