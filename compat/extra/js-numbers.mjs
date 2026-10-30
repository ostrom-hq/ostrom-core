// Generates js-numbers.json: doubles (as their 16 hex digits of IEEE-754 bits) and the text ECMAScript writes for them.
// Deterministic (xorshift seed). Run: node compat/extra/js-numbers.mjs > compat/extra/js-numbers.json
const buf = new DataView(new ArrayBuffer(8));
let s = 0x2545f4914f6cdd1dn;
const next = () => { s ^= (s << 13n) & 0xffffffffffffffffn; s ^= s >> 7n; s ^= (s << 17n) & 0xffffffffffffffffn; return s & 0xffffffffffffffffn; };
const fromBits = (b) => { buf.setBigUint64(0, b); return buf.getFloat64(0); };
const bitsOf = (d) => { buf.setFloat64(0, d); return buf.getBigUint64(0).toString(16).padStart(16, '0'); };
const values = [];
const fixed = [0.1, 0.2, 0.1 + 0.2, 1e21, 1e-7, 1e-6, 123e-20, 5e-324, 2.2250738585072014e-308, 2.225073858507201e-308,
  1.7976931348623157e308, 9007199254740993, 2 ** 53 + 2, 2 ** 60, 2 ** -1022, 2 ** -1074 * 3, 1 / 3, 2 / 3, 100, 1e20, 1.5e20,
  123456789012345680000, 4.35, 0.000001234, 1e300, 1e-300, 8.41e21, 5e-7, 1.0000000000000002, 0.9999999999999999,
  // two shortest neighbours exactly as close: the even one wins, below and above
  646628074277751.25, 646628074277751.75,
  // a shortest candidate on the rounding bound, kept with an even mantissa, refused with an odd one
  2 ** 54 + 8, 2 ** 54 + 24, 2 ** 54 + 4, 2 ** 54 + 228];
for (const d of fixed) { values.push(d, -d); }
for (let i = 0; i < 3000; i++) {
  const d = fromBits(next());
  if (Number.isFinite(d)) values.push(d);
}
for (let i = 0; i < 1000; i++) values.push(Number((Number(next() % 100000000n) / 10 ** Number(next() % 12n)).toPrecision(1 + Number(next() % 17n))));
console.log(JSON.stringify(values.map((d) => [bitsOf(d), String(d)])));
