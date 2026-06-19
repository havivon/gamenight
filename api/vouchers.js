const BASE = {
  'Accept': 'application/json',
  'Origin': 'https://www.10bis.co.il',
  'Referer': 'https://www.10bis.co.il/',
  'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36',
};

const STORES = [
  { id: 26698, name: 'שופרסל' },
  { id: 42962, name: 'ויקטורי' },
];

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  if (req.method === 'OPTIONS') return res.status(200).end();

  const { authCookie, refreshToken, userToken } = req.body;

  // Build Cookie header server-side — no browser restrictions
  const cookieStr = [
    authCookie   ? `Authorization=${authCookie}`   : '',
    refreshToken ? `RefreshToken=${refreshToken}` : '',
  ].filter(Boolean).join('; ');

  const authed = {
    ...BASE,
    ...(cookieStr ? { Cookie: cookieStr } : {}),
    ...(userToken ? { 'user-token': userToken, Authorization: `Bearer ${userToken}` } : {}),
  };

  const all = [];

  for (const store of STORES) {
    let list;
    try {
      const r = await fetch(
        `https://api.10bis.co.il/api/v1/VoucherCards/GetUserVouchers?restaurantId=${store.id}`,
        { headers: authed }
      );
      list = (await r.json())?.Data;
    } catch { continue; }

    if (!Array.isArray(list)) continue;

    for (const v of list) {
      const orderId = v.OrderId ?? v.orderId;
      if (!orderId) continue;
      try {
        const br = await fetch(
          `https://www.10bis.co.il/NextApi/GetOrderBarcode?culture=he-IL&uiCulture=he&orderId=${orderId}&resId=${store.id}`,
          { headers: authed }
        );
        const bd = await br.json();
        for (const bv of bd?.Data?.Vouchers ?? []) {
          all.push({
            store: store.name,
            orderId,
            used:    !!bv.Used,
            amount:  bv.Amount ?? 0,
            barcode: bv.BarcodeNumber ?? '',
            img:     bv.BarcodeImageUrl ?? bv.BarcodeImage ?? '',
            expiry:  bv.ExpirationDate ?? bv.ValidityDate ?? '',
          });
        }
      } catch { /* skip */ }
    }
  }

  res.json({ vouchers: all });
}
