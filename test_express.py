import hashlib, urllib.request, urllib.parse, json

KEY = 'oYhGrVWi4876'
CUSTOMER = '6C1E82D33A4AE54F0266E580BD730467'
NUMBER = '73720226955744'

# 快递100 中通标准编码：zhongtong。测试更多公司编码 + 检查账号支持的查询能力
companies = ['zhongtong', 'ZTO', 'yuantong', 'shunfeng', 'yunda', 'shentong', 'jd']
for com in companies:
    param_json = json.dumps({'com': com, 'num': NUMBER}, ensure_ascii=False)
    sign = hashlib.md5((param_json + KEY + CUSTOMER).encode('utf-8')).hexdigest().upper()
    body = urllib.parse.urlencode({'customer': CUSTOMER, 'sign': sign, 'param': param_json})
    req = urllib.request.Request(
        'https://poll.kuaidi100.com/poll/query.do',
        data=body.encode('utf-8'),
        headers={'Content-Type': 'application/x-www-form-urlencoded'})
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        raw = resp.read()
        # 尝试多种编码解码
        text = None
        for enc in ['utf-8', 'gbk', 'gb18030']:
            try:
                text = raw.decode(enc)
                break
            except:
                continue
        print(f'com={com:12s} -> {text[:200]}')
    except Exception as e:
        print(f'com={com:12s} -> ERROR: {e}')
