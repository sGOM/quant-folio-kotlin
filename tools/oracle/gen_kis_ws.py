"""KIS 실시간 체결통보 AES 복호화·파싱 정답지 — 원본과 같은 cryptography 로 암호문을 만들고 원본 함수로 풀어 본다.

엔진 모듈 정답지: ORACLE_OUT=backend/engine/src/test/resources/oracle tools/oracle/run.sh gen_kis_ws.py
"""
import base64

from cryptography.hazmat.primitives import padding as sym_padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from engine.fill_notice import aes_cbc_base64_decrypt, parse_fill_notice
from oracle_lib import save

KEY = "abcdefghijklmnopqrstuvwxyz012345"  # 32자(AES-256)
IV = "0123456789abcdef"


def enc(plain):
    p = sym_padding.PKCS7(128).padder()
    data = p.update(plain.encode("utf-8")) + p.finalize()
    e = Cipher(algorithms.AES(KEY.encode()), modes.CBC(IV.encode())).encryptor()
    return base64.b64encode(e.update(data) + e.finalize()).decode()


def fields(odno, side, sym, qty, price, n=23):
    f = ["x"] * n
    f[2], f[4], f[8], f[9], f[10] = odno, side, sym, qty, price
    return "^".join(f)


plains = [fields("0000123", "02", "005930", "3", "70100"), fields("0000124", "01", "000660", "0", "1"), fields(" ", "02", "005930", "1", "1"),
          fields("0000125", "01", "000660", "2", "abc"), "a^b^c", fields("0000126", "02", "삼성", "1", "1")]
cases = []
for p in plains:
    c = enc(p)
    d = aes_cbc_base64_decrypt(KEY, IV, c)
    n = parse_fill_notice(d)
    cases.append({"plain": p, "cipher": c, "decrypted": d, "notice": None if n is None else {"odno": n.odno, "symbol": n.symbol, "qty": n.qty, "price": str(n.price), "side": n.side}})
save("kis_ws", {"key": KEY, "iv": IV, "cases": cases})
print(len(cases))
