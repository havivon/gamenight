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

  const { email, otp, authenticationToken } = req.body;
  const r = await fetch('https://www.10bis.co.il/NextApi/GetUserV2', {
    method: 'POST',
    headers: HEADERS,
    body: JSON.stringify({ culture: 'he-IL', uiCulture: 'he', email, authenticationCode: otp, authenticationToken }),
  });

  const data = await r.json();

  // Extract cookies server-side (no browser restrictions here)
  let authCookie = '', refreshToken = '';
  (r.headers.getSetCookie?.() ?? []).forEach(c => {
    const kv = c.split(';')[0];
    if (kv.startsWith('Authorization='))  authCookie   = kv.slice('Authorization='.length);
    if (kv.startsWith('RefreshToken='))   refreshToken = kv.slice('RefreshToken='.length);
  });

  // Also try response body token as fallback
  const userToken = data?.Data?.userToken ?? data?.Data?.UserToken ?? '';

  res.json({ data, authCookie, refreshToken, userToken });
}
