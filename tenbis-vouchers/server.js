const express = require('express');
const axios = require('axios');
const path = require('path');

const app = express();
app.use(express.json());
app.use(express.static(__dirname));

const NEXT_API = 'https://www.10bis.co.il/NextApi';
const API_V1 = 'https://api.10bis.co.il/api/v1';

const HEADERS = {
  'Content-Type': 'application/json',
  'Accept': 'application/json, text/plain, */*',
  'Accept-Language': 'he-IL,he;q=0.9,en-US;q=0.8,en;q=0.7',
  'Accept-Encoding': 'gzip, deflate, br',
  'Origin': 'https://www.10bis.co.il',
  'Referer': 'https://www.10bis.co.il/',
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
  'sec-ch-ua': '"Not_A Brand";v="8", "Chromium";v="120", "Google Chrome";v="120"',
  'sec-ch-ua-mobile': '?0',
  'sec-ch-ua-platform': '"Windows"',
  'sec-fetch-dest': 'empty',
  'sec-fetch-mode': 'cors',
  'sec-fetch-site': 'same-site',
};

// Restaurant IDs for voucher stores
const RESTAURANTS = [
  { id: 26698, name: 'שופרסל' },
  { id: 42962, name: 'ויקטורי' },
];

app.post('/api/send-otp', async (req, res) => {
  const { email } = req.body;
  try {
    const response = await axios.post(
      `${NEXT_API}/GetUserAuthenticationDataAndSendAuthenticationCodeToUser`,
      { culture: 'he-IL', uiCulture: 'he', email },
      { headers: HEADERS }
    );
    res.json(response.data);
  } catch (err) {
    console.error('send-otp error:', err.response?.status, err.response?.data || err.message);
    res.status(500).json({ error: err.message, details: err.response?.data });
  }
});

app.post('/api/verify-otp', async (req, res) => {
  const { email, otp, authenticationToken } = req.body;
  try {
    const response = await axios.post(
      `${NEXT_API}/GetUserV2`,
      { culture: 'he-IL', uiCulture: 'he', email, authenticationCode: otp, authenticationToken },
      { headers: HEADERS, withCredentials: true }
    );

    const setCookies = response.headers['set-cookie'] || [];
    let authCookie = '';
    let refreshToken = '';
    setCookies.forEach(c => {
      const val = c.split(';')[0];
      if (val.startsWith('Authorization=')) authCookie = val.split('=').slice(1).join('=');
      if (val.startsWith('RefreshToken=')) refreshToken = val.split('=').slice(1).join('=');
    });

    res.json({ data: response.data, auth: { authCookie, refreshToken } });
  } catch (err) {
    console.error('verify-otp error:', err.response?.status, err.response?.data || err.message);
    res.status(500).json({ error: err.message, details: err.response?.data });
  }
});

app.post('/api/vouchers', async (req, res) => {
  const { authCookie, refreshToken } = req.body;
  const cookieHeader = `Authorization=${authCookie}; RefreshToken=${refreshToken}`;
  const authedHeaders = { ...HEADERS, Cookie: cookieHeader };

  try {
    const allVouchers = [];

    for (const restaurant of RESTAURANTS) {
      let voucherList;
      try {
        const resp = await axios.get(
          `${API_V1}/VoucherCards/GetUserVouchers?restaurantId=${restaurant.id}`,
          { headers: authedHeaders }
        );
        voucherList = resp.data?.Data;
      } catch (e) {
        console.warn(`Failed to fetch vouchers for restaurant ${restaurant.id}:`, e.message);
        continue;
      }

      if (!voucherList || !Array.isArray(voucherList)) continue;

      for (const voucher of voucherList) {
        const orderId = voucher.OrderId ?? voucher.orderId;
        if (!orderId) continue;

        try {
          const bResp = await axios.get(
            `${NEXT_API}/GetOrderBarcode?culture=he-IL&uiCulture=he&orderId=${orderId}&resId=${restaurant.id}`,
            { headers: authedHeaders }
          );
          const barcodeData = bResp.data?.Data;
          const vouchers = barcodeData?.Vouchers ?? [];

          for (const v of vouchers) {
            allVouchers.push({
              restaurantName: restaurant.name,
              orderId,
              used: v.Used,
              barcodeNumber: v.BarcodeNumber,
              barcodeImageUrl: v.BarcodeImageUrl ?? v.BarcodeImage,
              amount: v.Amount,
              validityDate: v.ValidityDate,
              expirationDate: v.ExpirationDate ?? v.ValidityDate,
            });
          }
        } catch (e) {
          console.warn(`Failed to fetch barcode for order ${orderId}:`, e.message);
        }
      }
    }

    res.json({ vouchers: allVouchers });
  } catch (err) {
    console.error('vouchers error:', err.message);
    res.status(500).json({ error: err.message });
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`Server running at http://localhost:${PORT}`));
